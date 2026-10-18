# Docker Compose — platform services

Run from the repository root:

```bash
docker compose -f deployments/docker/compose.yaml up -d
```

Profiles: `phase3` (Kafka), and the optional planes described in the compose
file. The slice acceptance run uses `compose.slice.yaml` (host-packaged jars)
and, for the no-broker run, `compose.outbox-only.yaml` — see
`tests/integration/vertical-slice.sh`.

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
