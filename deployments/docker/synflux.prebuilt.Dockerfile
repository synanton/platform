# syntax=docker/dockerfile:1
# Thin variant for hosts whose buildkit cannot reach the Maven repos
# (TLS resets observed 2026-10-18 on M70q): the boot jar is built on the
# HOST (warm Gradle cache) and only packaged here. Requires the host jar
# from `./gradlew :java:synflux:bootJar -x test` before `docker compose build`.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system synanton \
    && useradd --system --gid synanton --create-home synanton \
    && mkdir -p /content-cache && chown synanton:synanton /content-cache
USER synanton
WORKDIR /app
COPY java/synflux/build/libs/synflux*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
