package dev.mikeyku.wheelhouse.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Closes Supabase's Data API over this app's tables, on every boot.
 *
 * <p>Supabase serves every table in the public schema over a REST API to anyone holding the
 * project's publishable key, and by default grants its anon and authenticated roles every right
 * on each new table. The app never uses that API: it talks to Postgres directly, as the tables'
 * owner. But signing in with Supabase puts the publishable key in the page, and with the tables
 * open anybody could read the sessions table and sign in as whoever they liked.
 *
 * <p>So: row-level security on with no policies, the two roles' grants revoked, and the defaults
 * changed so a table Hibernate adds later starts closed. An owner bypasses row-level security, so
 * the app itself notices none of it. Idempotent, and a no-op wherever Supabase's roles do not
 * exist: H2, or a plain Postgres.
 */
@Component
public class DataApiLockdown {

    private static final Logger log = LoggerFactory.getLogger(DataApiLockdown.class);

    private final JdbcTemplate jdbc;

    public DataApiLockdown(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** After startup, so the tables Hibernate created or updated on this boot are included. */
    @EventListener(ApplicationReadyEvent.class)
    public void lock() {
        try {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (!"PostgreSQL".equals(product)) {
                return;
            }
            Boolean supabase = jdbc.queryForObject(
                    "select count(*) = 2 from pg_roles where rolname in ('anon', 'authenticated')", Boolean.class);
            if (!Boolean.TRUE.equals(supabase)) {
                return;
            }
            List<String> tables = jdbc.queryForList(
                    "select tablename from pg_tables where schemaname = 'public' and tableowner = current_user",
                    String.class);
            for (String table : tables) {
                jdbc.execute("alter table public.\"" + table.replace("\"", "\"\"") + "\" enable row level security");
            }
            jdbc.execute("revoke all on all tables in schema public from anon, authenticated");
            jdbc.execute("revoke all on all sequences in schema public from anon, authenticated");
            jdbc.execute("alter default privileges in schema public revoke all on tables from anon, authenticated");
            jdbc.execute("alter default privileges in schema public revoke all on sequences from anon, authenticated");
            log.info("Supabase Data API closed over {} tables", tables.size());
        } catch (RuntimeException e) {
            log.error("Could not close Supabase's Data API over the app's tables. Turn the Data API off "
                    + "in the Supabase dashboard until this is fixed: {}", e.toString());
        }
    }
}
