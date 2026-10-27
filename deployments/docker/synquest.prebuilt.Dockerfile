# syntax=docker/dockerfile:1
# Thin variant for hosts whose buildkit cannot reach the Maven repos
# (TLS resets observed 2026-10-18 on M70q): the boot jar is built on the
# HOST (warm Gradle cache) and only packaged here. Requires the host jar
# from `./gradlew :java:synquest:bootJar -x test` before `docker compose build`.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
WORKDIR /app
COPY java/synquest/build/libs/synquest*.jar app.jar
COPY demo-data/eval/retrieval-benchmark/ontology-v2.jsonl /app/data/ontology-v2.jsonl
COPY demo-data/eval/retrieval-benchmark/graph-v2.json /app/data/graph-v2.json
COPY demo-data/eval/retrieval-benchmark/dense-embeddings-v2.json /app/data/dense-embeddings-v2.json
COPY demo-data/eval/retrieval-benchmark/queries-v2.rb-fixed-g5.jsonl /app/data/queries-v2.rb-fixed-g5.jsonl
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
