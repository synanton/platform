FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts ./
COPY java/shared/common/ java/shared/common/
COPY java/topology/ java/topology/

RUN ./gradlew :java:topology:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S synanton -G synanton
USER synanton
WORKDIR /app
COPY --from=builder /workspace/java/topology/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
