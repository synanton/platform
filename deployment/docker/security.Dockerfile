FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts ./
COPY java/shared/common/ java/shared/common/
COPY java/security/ java/security/

RUN ./gradlew :java:security:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S synanton -G synanton
USER synanton
WORKDIR /app
COPY --from=builder /workspace/java/security/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
