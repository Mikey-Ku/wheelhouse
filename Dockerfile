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
USER wheelhouse

COPY --from=build --chown=wheelhouse:wheelhouse /src/target/*.jar app.jar

# Java 21 reads cgroup limits, so the heap sizes itself to whatever the host actually granted
# rather than to the machine's total memory. MaxRAMPercentage keeps headroom for the JVM's
# own off-heap use on a small container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
