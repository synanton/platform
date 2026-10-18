# syntax=docker/dockerfile:1
# Thin variant for hosts whose buildkit cannot reach the Maven repos
# (TLS resets observed 2026-10-18 on M70q): the boot jar is built in the
# content_extractor repo on the HOST
# (`./gradlew :java:extraction-gateway:bootJar -x test`) and only packaged here.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY java/extraction-gateway/build/libs/extraction-gateway*.jar app.jar
EXPOSE 8092 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
