# Synthetic Ingestion v1 — run-v2 preflight

**Ticket:** #966  
**Run:** `synthetic-ingestion-v1-run2`  
**Status:** BLOCKED at runtime readiness (Relix startup)  
**Captured:** 2026-11-27T19:20Z

## Environment identities

| Item | Identity | Status |
|---|---|---|
| Platform commit | `c3c833d5ab12dfeb379c3f2a21863d99f93205a8` (`main`) | PASS |
| Content Extractor commit | `ecc339a2aa2df49eca3eb75dc0f453c9bdc525e3` (`main`, PR #31) | PASS |
| Image tag | `run2-c3c833d5ab12` | PASS |

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
| Fresh data root | `/tmp/opencode/synthetic-ingestion-run2-data`; initially empty | PASS |
| Compose project | `synanton-local-real-demo`; `local-real-demo` profile | PASS |
| Production services/credentials | no remote production endpoints in resolved config | PASS |
| Tenant | `synthetic-ingestion` | PASS |
| Graph projection | `EXTRACTION_GRAPH_ENABLED=false` | PASS |

## Runtime readiness

Started with registry images and `--no-build`.

Healthy:

- postgres
- cassandra
- minio
- extraction-gateway
- security
- synflux
- synquest
- neo4j
- syntology
- mock-extraction

Failed:

- `relix` — `Exited (1)`

Relix startup error:

```text
Neo4j is missing required constraints: [source_revision_fence_unique]
```

Root cause: Compose `neo4j-init` creates six of the seven constraints required by
`Neo4jSchemaVerifier`; it omits `source_revision_fence_unique`. The homelab Kubernetes init
already creates it. Separate issue: #967.

| Check | Status |
|---|---|
| Extraction client enabled at runtime | PASS |
| Extraction endpoint | `extraction-gateway:9091` — PASS |
| Gateway health from synflux | `{"status":"UP"}` — PASS |
| Synflux health | `{"status":"UP"}` — PASS |
| Synquest health | `{"status":"ready"}` — PASS |
| Graph/consumer disabled | PASS |
| Relix readiness | FAIL |
| Complete stack readiness | FAIL |

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

**Assignment 1: BLOCKED.**

Stop condition: the stack is not fully ready because Relix exits on a missing required Neo4j
constraint. SCEP connectivity and production-path static checks pass, but runtime readiness is not
complete. No fixture ingestion was performed.

No production code, fixture, gold, ontology, schema, validator, baseline, or evaluation artifact
was changed. The deployment defect is tracked separately as #967.

**Next action:** fix #967, restart the isolated stack, and repeat the runtime-readiness gate.
