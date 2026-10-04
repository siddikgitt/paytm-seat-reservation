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
# exit after refresh and dump the loaded classes. Cuts JVM startup substantially on small CPU shares.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -Dspring.main.banner-mode=off -Dlogging.level.root=WARN \
        -jar app.jar || true
USER app
# C1-only JIT (TieredStopAtLevel=1) halves startup on a 0.1 CPU free instance and keeps the compiler from
# competing with request threads mid-burst. On a larger plan, override JAVA_TOOL_OPTIONS without it.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=48m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k -XX:TieredStopAtLevel=1 -XX:SharedArchiveFile=/app/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
