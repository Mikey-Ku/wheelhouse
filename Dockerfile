# Two stages so the runtime image carries a JRE and a jar rather than Maven and a source tree.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Wrapper and descriptor first: dependency resolution is the slow layer and it only needs to
# rerun when the pom changes, not when a Java file does.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# The scheduler and the ESPN client both make outbound calls; running as root buys nothing.
#
# The working directory has to be writable by that user, not merely readable. WORKDIR creates
# /app owned by root, and the Sleeper client calls createDirectories on .cache before every
# player-list and projection fetch, so an unwritable /app throws on the first one. The failure
# is quiet in the worst way: Postgres is fine, so the container boots and the health check
# reports UP, and the only symptom is a wheel with no players on it.
RUN useradd --create-home --shell /usr/sbin/nologin wheelhouse \
 && mkdir -p /app/.cache \
 && chown -R wheelhouse:wheelhouse /app

# Seed the Sleeper cache at build time. Without it every cold start begins with a 14MB
# download parsed on a tenth of a CPU, and the stale-copy fallback in SleeperClient has
# nothing to fall back to when Sleeper is unreachable. Docker fetches this once per build;
# the running app refreshes it in place once it ages past the cache TTL.
ADD --chown=wheelhouse:wheelhouse https://api.sleeper.app/v1/players/nfl /app/.cache/players-nfl.json
# ADD from a URL stamps the file with 1970 when the origin sends no Last-Modified, and the
# client judges freshness by mtime, so without this the seed counts as stale on every boot and
# is only ever the fallback. Dated at build time it is served outright for the first day.
RUN touch /app/.cache/players-nfl.json
USER wheelhouse

COPY --from=build --chown=wheelhouse:wheelhouse /src/target/*.jar app.jar

# Memory on a 512MB instance, every number below measured rather than guessed.
#
# Java 21 reads cgroup limits, so the heap sizes itself to whatever the host actually granted
# rather than to the machine's total memory. The customary 70% is far too much here: with the
# heap allowed 282MB the process idled at 457MB before serving a request and was killed by the
# fourteenth archive week. The live heap after a full GC is about 100MB with eight archive
# weeks resident, so 45% (230MB) is generous and leaves the rest of the container to the JVM's
# own overhead, which native memory tracking put at roughly 190MB: 78MB metaspace, 31MB symbol
# table, 27MB JIT code cache, and 17MB of compiler arenas that only C2 needs.
#
#   TieredStopAtLevel=1    C1 only. Halves the code cache and drops the compiler arenas; on a
#                          tenth of a CPU the JIT's own time was costing more than C2 returned.
#   ReservedCodeCacheSize  ceiling on what C1 can still grow to.
#   MaxMetaspaceSize       ceiling, not a saving. Turns runaway class loading into a clean error
#                          instead of a container kill.
#   Min/MaxHeapFreeRatio   let the serial collector hand memory back after a full GC, so RSS
#                          follows live data down as well as up.
#   MALLOC_ARENA_MAX=2     glibc gives every thread its own malloc arena and never compacts
#                          them; 124 anonymous mappings and 23MB of untracked growth over ten
#                          archive loads was that. Two arenas is the standard fix for a JVM in
#                          a small container.
#
# Result on the same 20-week test that killed the untuned image: 337MB idle, 370MB after
# twenty archive weeks, and flat once the eight-week cap engages.
ENV MALLOC_ARENA_MAX=2
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=128m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
