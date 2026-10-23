# Local Real Demo v1 acceptance (2026-10-23)

**Result:** the isolated `local-real-demo` Compose profile ingests the pinned
real PDF through the public `/v1` API, and the search, provenance,
canonical-page/section, and idempotency checks pass from a clean local state.

This record covers [#399](https://github.com/synanton/platform/issues/399). It
is local Compose acceptance, distinct from the homelab run recorded in
[`p0-ingestion-search-2026-10-21.md`](p0-ingestion-search-2026-10-21.md).

## Command and environment

```bash
./scripts/run-local-real-demo.sh up
```

| Item | Value |
|---|---|
| profile | `local-real-demo` |
| Compose project | `synanton-local-real-demo` (fixed; not `COMPOSE_PROJECT_NAME`) |
| host ports | synflux `18090`, synquest `18083` |
| tenant | `local-real-demo` |
| fixture | `demo-data/documents/mental-health-report-2010.pdf` |
| fixture SHA-256 | `d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f` |
| content_extractor | `53a81ce5e408cb3e0f691d8d0434a6695c76482b` |
| images | `synanton-local-real-demo/{synflux,synquest,extraction-gateway}:local` |
| embedding/Kafka | disabled for this profile; search leg is BM25 |

The script preflights the fixture digest and the pinned extraction-gateway
checkout, builds host boot jars, packages the three platform images from the
`*.prebuilt.Dockerfile` variants, starts the profile, and waits for the
Compose health checks and both service `/health` endpoints. No sleep-based
start ordering is used: dependencies are enforced by Compose health conditions
and the script's health gates.

The exact platform commit under test is recorded in each generated
`evidence.json` and in the CI artifact. This document is committed with the
implementation; the PR records the CI run and the merge commit.

### Demo vs harness and API path

- This demo is the real-PDF acceptance path. It is separate from the
  generated-Markdown harness in `tests/integration/vertical-slice.sh` and the
  `compose.slice.yaml` override.
- Ingestion enters only through `/v1/sources` and multipart
  `/v1/ingestion-runs`. The legacy `/ingest/run` route is not used and remains
  tracked for removal in [#329](https://github.com/synanton/platform/issues/329).
- The `local-real-demo` workflow is a separate, path-filtered workflow. It is
  not attached to unrelated PRs; it runs on changes to the demo, its services,
  its fixture, or this acceptance record.

## Acceptance evidence

The run produced:

```json
{
  "command": "./scripts/run-local-real-demo.sh up",
  "compose_project": "synanton-local-real-demo",
  "tenant": "local-real-demo",
  "source_id": "mental-health-report-2010",
  "run_id": "daae67d7-c17e-3d0a-a11c-58162b11782e",
  "operation_id": "8cf03072-6663-482e-ae6a-9d88757d92b2",
  "source_version_id": "5cc04cac-f85d-530e-a967-f9e7b75cf546",
  "fixture_sha256": "d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f",
  "search_hit_object_id": "c799bd65-394c-56ce-a7ac-a871fd5587df:5",
  "canonical_chunk_page_metadata": {
    "chunk_ordinal": 5,
    "page_start": 2,
    "page_end": 2,
    "section_path": "APPENDIX A: DATA SOURCE DESCRIPTIONS"
  },
  "idempotency": "same operation_id; one source version after replay"
}
```

The complete machine-readable output is written at runtime to
`deployments/docker/data-local-real-demo/evidence.json`.

## Assertions exercised

1. `POST /v1/sources` creates an upload source; multipart
   `POST /v1/ingestion-runs` returns `202` and the operation reaches
   `SUCCEEDED` / `FULL`.
2. `GET /v1/sources/{source_id}/versions` returns exactly one version whose
   digest equals the pinned PDF SHA-256.
3. `POST /v1/search-projection/rebuild` succeeds; `POST /v1/search` returns a
   hit whose canonical chunk contains the known text
   `overall response rate for fiscal year 2007 was 78 percent`.
4. The hit's live `provenance_ref` resolves and names the ingested source
   version, the original document digest, and a Content Cache reference.
5. The canonical chunk row carries a real page range (`2–2`) and a non-empty
   section path (`APPENDIX A: DATA SOURCE DESCRIPTIONS`). These fields are
   verified in canonical storage, not assumed from the public search shape
   (public page-range exposure remains [#338](https://github.com/synanton/platform/issues/338)).
6. Repeating the identical upload with the same `Idempotency-Key` returns the
   same operation, leaves exactly one source version, and returns the same
   canonical search hit.

## Lifecycle checks

- `down` removes the profile's containers and network while preserving the
  host data roots and the `content-cache` named volume.
- A subsequent `up` against the preserved data returned the same operation and
  the same single source version, then repeated all acceptance assertions.
- `reset` explicitly removes the profile's containers, its project-scoped
  named volume (`synanton-local-real-demo_content-cache`), and the default
  isolated data root. The project name is fixed, so it cannot resolve to the
  main stack's `docker_content-cache`; the shared `synanton-demo` network is
  not used. `reset` refuses to delete a custom
  `LOCAL_REAL_DEMO_DATA_ROOT`.

## Extractor pin procedure

The CI checkout and `LOCAL_REAL_DEMO_EXPECTED_EXTRACTOR_COMMIT` both pin
`53a81ce5e408cb3e0f691d8d0434a6695c76482b`. To move the pin deliberately:

1. Build and run the demo locally against the new extractor commit.
2. Update both pins in `.github/workflows/local-real-demo.yml`.
3. Rerun the acceptance workflow and update this record with the new commit
   and observed evidence.

A private extractor checkout needs a token with read access; the workflow uses
the default `GITHUB_TOKEN`, which is sufficient only while the repository is
public or the token is granted access.

## Known boundaries

- This is a local, single-tenant BM25 acceptance path. It does not exercise
  vector retrieval, Kafka dispatch, the homelab cluster, or the legacy
  `/ingest/run` route.
- The profile requires a sibling `content_extractor` checkout and a local
  Docker daemon with enough memory for Cassandra plus the three platform
  services.
- EWP-2 publisher work remains gated on #399 being accepted and marked On
  Track; this record supplies the local acceptance evidence for that gate.
