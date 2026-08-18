FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/shared ./java/shared
COPY java/synapt ./java/synapt
RUN ./gradlew :java:synapt:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/synapt/build/libs/synapt*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
