# Synthetic Ingestion v1 — run-v2 preflight

**Ticket:** #966  
**Run:** `synthetic-ingestion-v1-run2`  
**Status:** BLOCKED before stack startup / fixture ingestion  
**Captured:** 2026-11-27T18:27Z

## Environment identities

| Item | Identity | Status |
|---|---|---|
| Platform commit | `c3c833d5ab12dfeb379c3f2a21863d99f93205a8` (`main`) | PASS |
| Content Extractor commit | `ecc339a2aa2df49eca3eb75dc0f453c9bdc525e3` (`main`, PR #31) | PASS |
| Content Extractor working tree | same tree as `e22945a` | PASS |
| Platform image digests | not produced for this run | BLOCKED |
| Content Extractor gateway digest | not produced for this run | BLOCKED |

No `run2` images exist. Existing `:local` images are older and were not used:

- `synanton-local-real-demo/synflux:local` — `sha256:d8b31a5c9ce0...`
- `synanton-local-real-demo/synquest:local` — `sha256:2577b8459575...`
- `synanton-local-real-demo/extraction-gateway:local` — `sha256:fa37fd918e1c...`

## Build blocker

`./scripts/run-local-real-demo.sh up` did not reach Compose startup. It remained in the
`Building host boot jars and demo images...` step. Two long-running attempts were terminated by
the execution timeout; no containers were created.

Individual host builds subsequently succeeded:

- Platform boot jars: `BUILD SUCCESSFUL in 25s`
- Syntology boot jar: `BUILD SUCCESSFUL in 13s`
- Content Extractor gateway boot jar: `BUILD SUCCESSFUL in 6s`

A direct image build then failed while resolving the Dockerfile frontend:

```text
#2 resolve image config for docker-image://docker/dockerfile:1
#2 CANCELED
ERROR: failed to build: failed to solve: Canceled: context canceled
```

The build was bounded at 300 seconds and exited `124`. This is a Docker build/network blocker,
not a fixture or production-code result. No fallback image or alternate build path was used.

## Isolation

| Check | Observation | Status |
|---|---|---|
| Fresh data root | `/tmp/opencode/synthetic-ingestion-run2-data` created; directories empty | PASS |
| Compose project | `synanton-local-real-demo`; `local-real-demo` profile | PASS |
| Running services | none | PASS |
| Listening demo ports | none on 18090/18083/18081/18084/18082/17474/17687 | PASS |
| Production services/credentials | no remote production endpoints in resolved Compose config | PASS |
| Stack started | not started | BLOCKED |

The data root contains only empty service directories. No schema, container startup, or ingest
state exists.

## Extraction configuration and reachability

Resolved Compose configuration reports:

- `EXTRACTION_CLIENT_ENABLED=true`
- `EXTRACTION_ENDPOINT=extraction-gateway:9091`
- `EXTRACTION_PROVIDER=regex`
- `EXTRACTION_CONSUMER_ENABLED=false`
- `EXTRACTION_GRAPH_ENABLED=false`

| Check | Status |
|---|---|
| Client enabled in configuration | PASS |
| Graph disabled | PASS |
| Content Extractor reachable | BLOCKED — stack not running |
| Runtime extraction request | BLOCKED — not attempted |

## Production-path verification

Static verification:

- Production `ExtractionStage` requires the extraction client and throws when it is disabled.
- It consumes SCEP `DocumentPayload` and does not construct local Tika, PDFBox, Markdown, or
  `DocumentStructureService`.
- `ExtractionStageProductionPathTest.neverConstructsPlatformStructureServiceOnSuccessOrFailure`
  passed.

| Check | Status |
|---|---|
| No local-parser fallback in production code | PASS |
| Runtime logs prove SCEP use | BLOCKED — no runtime started |
| No fallback observed at runtime | BLOCKED — no runtime started |

## Frozen fixture and ontology

| Item | Identity | Status |
|---|---|---|
| Source SHA-256 | `db4f2e992df82deb62578b0245eed4e99c04cb6bc16c88674820fbcb73add063` | PASS |
| Gold SHA-256 | `f12619bd00056e7e996c776f288c2c814840dae31549936e9cbb61b41f04411b` | PASS |
| `sha256sum -c SHA256SUMS` | source and gold OK | PASS |
| Gold validator | `{"chunks": 14, "mentions": 25, "negativeAssertions": 4, "relations": 15, "retrieval": 3, "retrievalSupported": 0}` | PASS |
| Ontology release pin | `47fe38f8-9f72-481b-826a-6c5b12a6027a` | PASS |
| Ontology release digest | `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c` | PASS |
| Deployed ontology identity | not observable; stack not started | BLOCKED |

Historical note: the immutable #944 `run-v1/run-manifest.json` records gold SHA-256
`9bd2d17d...`, while the frozen fixture checksum is `f12619bd...`. This run uses the current
frozen `SHA256SUMS`; #944 has not been altered.

## Gate result

**Assignment 1: BLOCKED.**

Stop conditions reached:

- Current-run image identity cannot be established.
- The stack cannot be started without resolving the Docker build/network blocker.
- SCEP reachability and runtime-path evidence therefore cannot be collected.

No fixture ingestion was performed. No production code, fixture, gold, ontology, schema,
validator, baseline, or evaluation artifact was changed.

**Next action:** restore Dockerfile frontend/base-image resolution or provide current prebuilt
images, then repeat Assignment 1. Do not add a fallback or bypass the existing Compose path.
