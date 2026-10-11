# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# --mount keeps the Maven cache across rebuilds; tests run via `mvn verify`.
RUN --mount=type=cache,target=/root/.m2 mvn -q -Dmaven.test.skip=true package

# ---- runtime ----
FROM eclipse-temurin:25-jre
# Pre-create the driver cache so a fresh named volume mounted here inherits
# uid 10001 and stays writable by the unprivileged user.
RUN useradd -m -u 10001 jabuti \
    && install -d -o jabuti -g jabuti /home/jabuti/.jabuti
WORKDIR /deployments
COPY --from=build /build/target/quarkus-app/ ./quarkus-app/
ENV JABUTI_CONFIG=/config/datasources.yaml \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER jabuti
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-app/quarkus-run.jar"]
