FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/synanton-llm-client ./java/synanton-llm-client
COPY java/gateway ./java/gateway
RUN ./gradlew :java:gateway:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/gateway/build/libs/gateway*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
