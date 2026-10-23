# Docker Compose — platform services

Run from the repository root:

```bash
docker compose -f deployments/docker/compose.yaml up -d
```

Profiles: `phase3` (Kafka), and the optional planes described in the compose
file. The slice acceptance run uses `compose.slice.yaml` (host-packaged jars)
and, for the no-broker run, `compose.outbox-only.yaml` — see
`tests/integration/vertical-slice.sh`.

## Local Real Demo v1 (#399)

The real-PDF demo runs in its own Compose project and data directory. From the
platform repository root, with the sibling `content_extractor` checkout
available at `../content_extractor`, run:

```bash
./scripts/run-local-real-demo.sh up
```

The command builds and starts only the `local-real-demo` profile services,
uploads the pinned `mental-health-report-2010.pdf` through `/v1/sources` and
multipart `/v1/ingestion-runs`, then checks the search fact, live provenance,
canonical page/section metadata, and same-key idempotent replay. It leaves the
stack up and writes machine-readable evidence to
`deployments/docker/data-local-real-demo/evidence.json`.

```bash
./scripts/run-local-real-demo.sh test   # re-run acceptance against the stack
./scripts/run-local-real-demo.sh down   # remove containers; preserve data
./scripts/run-local-real-demo.sh reset  # explicit removal of isolated data
```

The profile defaults to host ports 18090 (synflux) and 18083 (synquest), has an
isolated Compose project and runtime-data directory, and does not use the
legacy `/ingest/run` route. `CONTENT_EXTRACTOR_ROOT` can point to a checkout in
another location. Setting a custom `LOCAL_REAL_DEMO_DATA_ROOT` is supported;
`reset` intentionally refuses to delete custom paths.

## Image pulls

Direct Docker Hub pulls can hang on some networks (observed on the M70q
dev host, 2026-10-18: `docker pull apache/kafka:3.7.0` retried forever).
The Google mirror works:

```bash
docker pull mirror.gcr.io/library/apache/kafka:3.7.0
docker tag  mirror.gcr.io/library/apache/kafka:3.7.0 apache/kafka:3.7.0
```

Use the mirror path for any `docker.io/library/*` image when a pull stalls —
retag to the name the compose file expects. This is the same mirror-first
rule the homelab registry enforces on the cluster side (workers have no
assumed egress): pull on a machine with internet, retag, push to
`local-registry`.

## MinIO source

MinIO withdrew its community images from Docker Hub **and** Quay on
2025-10-23 ([source-only distribution](https://github.com/minio/minio/issues/21647)).
This stack uses **Chainguard's free rebuilds** instead
([announcement](https://www.chainguard.dev/unchained/secure-and-free-minio-chainguard-containers)):

- `chainguard/minio` (server) and `chainguard/minio-client` (`mc`), pinned by
  digest in `mirror-images.sh` and the smoke compose.
- Consequence of the minimal images: **the client has no shell** — `mc` runs
  directly and reads the endpoint+credentials from `MC_HOST_<alias>` (no
  `mc alias set` step).
- The server runs as **uid 65532**: its data directory must be writable by it.
  The compose stack uses `deployments/docker/data-cg/minio` (prepared 0777 by
  whoever brings the stack up — the acceptance script does it; the old
  root-owned `data/` tree cannot be chmodded without sudo).

To move a pin deliberately:

```bash
docker pull chainguard/minio:latest
docker inspect --format '{{index .RepoDigests 0}}' chainguard/minio:latest
```
