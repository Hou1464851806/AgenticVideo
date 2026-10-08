FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /app/backend
COPY backend/pom.xml backend/checkstyle.xml ./
COPY backend/src ./src
COPY contracts /app/contracts
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify
FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c AS media-runtime
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl && rm -rf /var/lib/apt/lists/*
RUN test "$(dpkg-query -W -f='${Version}' ffmpeg)" = '7:6.1.1-3ubuntu5'
RUN useradd --create-home --uid 10001 app && mkdir /work && chown app:app /work
WORKDIR /app
USER app
FROM media-runtime
COPY --from=build /app/backend/target/backend-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
