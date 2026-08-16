package dev.mikeyku.wheelhouse.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntryRepository extends JpaRepository<EntryRecord, String> {

    Optional<EntryRecord> findByContestIdAndOwnerIgnoreCase(String contestId, String owner);

    List<EntryRecord> findByContestId(String contestId);

    /** A player's whole history, newest week first. */
    List<EntryRecord> findByOwnerIgnoreCaseOrderByCreatedAtDesc(String owner);
}
