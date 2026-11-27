# Synthetic Ingestion v1 — run-v2 preflight

**Ticket:** #966  
**Run:** `synthetic-ingestion-v1-run2`  
**Status:** PASS — clean-start runtime readiness; fixture not ingested  
**Captured:** 2026-11-27T19:35Z

## Environment identities

| Item | Identity | Status |
|---|---|---|
| Platform commit | `c3c833d5ab12dfeb379c3f2a21863d99f93205a8` (`main`) | PASS |
| Content Extractor commit | `ecc339a2aa2df49eca3eb75dc0f453c9bdc525e3` (`main`, PR #31) | PASS |
| Image tag | `run2-c3c833d5ab12` | PASS |
| Compose configuration revision | `8205cdb6` | PASS |

Built and verified images:

```text
local-registry:5000/synanton/synflux@sha256:149dbd3523e1727332f8c37dffea069bd67a3b3cd4c0b92a8311c6f531cff582
local-registry:5000/synanton/synquest@sha256:a1e7838724a8a569b0b25ae963ece18e35550dece162546a01b220a5c716e1da
local-registry:5000/synanton/security@sha256:fa9c057d97e81f71ab66f5d65b6ac733e7b4448bf48d696c94c5981a9a257519
local-registry:5000/synanton/relix@sha256:8041fe74d8b113e24853fd2a8f91177a3baaa001a5e4aae5217924f32409ab30
local-registry:5000/synanton/syntology@sha256:3a5f4b0a8bf94628ce5d4ca1b28ada4e16591500dc80683df7814de6ceb34ed9
local-registry:5000/synanton/extraction-gateway@sha256:6e0ff9d602f3862d1cb9ea2ef22c55c1f5c682f60bf8baf95cd8db6f299dc856
```

The stack containers use these exact image references.

## Isolation

| Check | Observation | Status |
|---|---|---|
| Fresh data root | `/tmp/opencode/synthetic-ingestion-run2-clean-2`; initially empty | PASS |
| Compose project | `synanton-local-real-demo`; `local-real-demo` profile | PASS |
| Production services/credentials | no remote production endpoints in resolved config | PASS |
| Tenant | `synthetic-ingestion` | PASS |
| Graph projection | `EXTRACTION_GRAPH_ENABLED=false` | PASS |

## Neo4j initialization fix (#967)

Compose `neo4j-init` was missing `source_revision_fence_unique`. Fixed in `8205cdb6` and covered by:

- `Neo4jSchemaManifestConsistencyTest.localComposeInitCoversEveryRequiredConstraint`
- `scripts/ci/check-local-real-demo-neo4j-constraints.sh`

Live clean-start check:

```text
Required constraints (7): ... source_revision_fence_unique
Observed constraints (7): ... source_revision_fence_unique
PASS: local-real-demo Neo4j has every required constraint
```

## Runtime readiness

All services healthy on the fresh data root:

```text
cassandra              healthy
extraction-gateway     healthy
minio                  healthy
mock-extraction        healthy
neo4j                  healthy
postgres               healthy
relix                  healthy
security               healthy
synflux                healthy
synquest               healthy
syntology              healthy
```

| Check | Status |
|---|---|
| Neo4j required constraints | 7 / 7 — PASS |
| Relix readiness | POST `/health` → `{"status":"ready"}` — PASS |
| Synflux health | `{"status":"UP"}` — PASS |
| Synquest health | `{"status":"ready"}` — PASS |
| Gateway process health | `{"status":"UP", ...}` — PASS |
| Gateway reachable from synflux | `http://extraction-gateway:8092/actuator/health` → UP — PASS |
| Extraction client enabled | `EXTRACTION_CLIENT_ENABLED=true` — PASS |
| Extraction endpoint | `extraction-gateway:9091` — PASS |
| Extraction provider | `regex` — PASS |
| Extraction consumer | disabled — PASS |
| Graph projection | disabled — PASS |

## Production-path verification

Static checks:

- Production `ExtractionStage` requires the extraction client and throws when it is disabled.
- It consumes SCEP `DocumentPayload` and does not construct local Tika, PDFBox, Markdown, or
  `DocumentStructureService`.
- `ExtractionStageProductionPathTest.neverConstructsPlatformStructureServiceOnSuccessOrFailure`
  passed.

| Check | Status |
|---|---|
| No local-parser fallback in production code | PASS |
| SCEP gateway reachable from synflux | PASS |
| Runtime extraction request | NOT_RUN — fixture ingestion not started |
| Runtime fallback observed | NOT_RUN — no extraction request yet |

## Frozen fixture and ontology

| Item | Identity | Status |
|---|---|---|
| Source SHA-256 | `db4f2e992df82deb62578b0245eed4e99c04cb6bc16c88674820fbcb73add063` | PASS |
| Gold SHA-256 | `f12619bd00056e7e996c776f288c2c814840dae31549936e9cbb61b41f04411b` | PASS |
| `sha256sum -c SHA256SUMS` | source and gold OK | PASS |
| Gold validator | `{"chunks": 14, "mentions": 25, "negativeAssertions": 4, "relations": 15, "retrieval": 3, "retrievalSupported": 0}` | PASS |
| Ontology release pin | `47fe38f8-9f72-481b-826a-6c5b12a6027a` | PASS |
| Ontology release digest | `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c` | PASS |
| Deployed ontology identity | not yet observed | NOT_RUN |

Historical note: the immutable #944 `run-v1/run-manifest.json` records gold SHA-256
`9bd2d17d...`, while the frozen fixture checksum is `f12619bd...`. This run uses the current
frozen `SHA256SUMS`; #944 has not been altered.

## Gate result

**Assignment 1: PASS.**

Clean-start runtime readiness is established on a fresh data root. SCEP connectivity, production
path wiring, service health, and the complete Relix Neo4j constraint contract are verified.

No fixture ingestion was performed. No production code, fixture, gold, ontology, schema,
validator, baseline, or evaluation artifact was changed.

**Next action:** request approval to ingest the frozen fixture once through the existing
local-real-demo path.
