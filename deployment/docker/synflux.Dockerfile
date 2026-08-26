FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/shared ./java/shared
COPY java/ingestion-cache ./java/ingestion-cache
COPY java/synvault ./java/synvault
COPY java/synanton-llm-client ./java/synanton-llm-client
COPY java/extraction-contract ./java/extraction-contract
COPY java/synflux ./java/synflux
RUN ./gradlew :java:synflux:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/synflux/build/libs/synflux*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
