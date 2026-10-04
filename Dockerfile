# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/app.jar extract --destination /app

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /app/ ./
# AppCDS training run: start the context without a database (Flyway off, Hikari connects lazily),
# exit after refresh and dump the loaded classes for reuse at runtime.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -Dspring.main.banner-mode=off -Dlogging.level.root=WARN \
        -jar app.jar || true
USER app
# C1-only JIT (TieredStopAtLevel=1) bounds compilation work on the small free instance.
# Startup and throughput measurements are recorded in VERIFICATION.md.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=48m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k -XX:TieredStopAtLevel=1 -XX:SharedArchiveFile=/app/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
