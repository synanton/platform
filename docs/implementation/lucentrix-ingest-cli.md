# Lucentrix ingestion CLI

Lucentrix is a sibling Maven project that crawls external sources and **pushes raw bytes** into Synanton. Synflux still owns parse → chunk → enrich → embed.

## Contract

1. Lucentrix `synanton-target-plugin` `POST`s document bytes to synvault `POST /content/{tenant}` with `X-Source-Uri` and `Content-Type`.
2. Synvault `ContentPushPort` stores the blob in MinIO under `{tenant}/{content_ref_id}` (`content_ref_id` = UUIDv5 over `tenant:sha256`).
3. Lucentrix then `POST /ingest` on synflux with `source=synvault` and `path` set to comma-separated content ref ids.
4. Synflux `IngestionJobRunner` lists those ids via the `synvault` `ContentAdapter` (`PushedContentAdapter`) and runs the existing pipeline.

## Demo

```bash
# Synanton
docker compose -f deployment/docker/compose.yaml up -d cassandra minio minio-init synvault synflux

# Lucentrix (separate repo)
cd ../lucentrix
mvn -pl ingestion/crawler -am package
java -jar ingestion/crawler/target/crawler-0.1.0-SNAPSHOT.jar
# UI: http://localhost:8095  then Start
```

Inspect synvault: `curl http://localhost:8088/manifest/demo`
