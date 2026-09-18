# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/shared ./java/shared
COPY java/ingestion-cache ./java/ingestion-cache
COPY java/synanton-llm-client ./java/synanton-llm-client
COPY java/synquest ./java/synquest
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :java:synquest:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S synanton && adduser -S -G synanton synanton \
    && mkdir -p /var/lib/synquest && chown synanton:synanton /var/lib/synquest
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/synquest/build/libs/synquest*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
