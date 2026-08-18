FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts ./
COPY java/shared/common/ java/shared/common/
COPY java/syntology/ java/syntology/

RUN ./gradlew :java:syntology:bootJar -PskipUi --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton -g 2000 && adduser -S alice -G synanton -u 1001
USER alice
WORKDIR /app
COPY --from=builder /workspace/java/syntology/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
