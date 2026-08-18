FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/planner ./java/planner
RUN ./gradlew :java:planner:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/planner/build/libs/planner*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
