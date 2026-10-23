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

The local clean-checkout run used commit
`b1519a913ff8c58e3af3f747bd481bea6a609ba7` from a fresh clone with no demo
containers or volumes present. The next commit only updates this record; CI
records its own head SHA in the uploaded artifact.

```json
{
  "command": "./scripts/run-local-real-demo.sh up",
  "platform_commit": "b1519a913ff8c58e3af3f747bd481bea6a609ba7",
  "extractor_commit": "53a81ce5e408cb3e0f691d8d0434a6695c76482b",
  "compose_project": "synanton-local-real-demo",
  "profile": "local-real-demo",
  "image_tag": "synanton-local-real-demo/{synflux,synquest,extraction-gateway}:local",
  "tenant": "local-real-demo",
  "source_id": "mental-health-report-2010",
  "run_id": "95222bcd-c3cb-382c-912f-a17212e84207",
  "operation_id": "fb73464f-e612-46ff-bd5f-c885414e36cc",
  "operation_outcome": "FULL",
  "source_version_id": "5cc04cac-f85d-530e-a967-f9e7b75cf546",
  "fixture_sha256": "d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f",
  "search_hit_object_id": "c799bd65-394c-56ce-a7ac-a871fd5587df:5",
  "search_hit_source_uri": "manual-upload://mental-health-report-2010/mental-health-report-2010.pdf",
  "search_hit_chunk_sha256": "3b720ffdbafc3a353c27f66ecbbb351d9c8917597eead6f9ff766fee935de9c2",
  "canonical_chunk_page_metadata": {
    "chunk_ordinal": 5,
    "page_start": 2,
    "page_end": 2,
    "section_path": "APPENDIX A: DATA SOURCE DESCRIPTIONS"
  },
  "page_range_semantics": "inclusive (single page has start == end)",
  "latency": {
    "ingest_operation_ms": 2409,
    "ingest_to_searchable_ms": 3404,
    "search_p50_ms": 117,
    "search_samples": 10
  },
  "idempotency": "same operation_id; one source version after replay"
}
```

Latency is local, warm-cache, single-tenant BM25: 2.4 s from upload to
operation completion, 3.4 s from upload to searchable, and 117 ms search p50
over ten calls. These are not capacity claims.

The complete machine-readable output is written at runtime to
`deployments/docker/data-local-real-demo/evidence.json` and uploaded by CI.

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
5. The canonical chunk row carries exactly the expected inclusive page range
   (`page_start=2`, `page_end=2`, i.e. the single page 2) and the expected
   section path (`APPENDIX A: DATA SOURCE DESCRIPTIONS`). These fields are
   verified in canonical storage, not assumed from the public search shape
   (public page-range exposure remains [#338](https://github.com/synanton/platform/issues/338)).
   A change to the rendering or semantics of a single-page range fails this
   assertion instead of silently passing.
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

## Clean-checkout and CI repetition

After the implementation commit, the demo was reset (containers, network,
project volume, and default data root removed) and run from a fresh clone of
`b1519a9` with only `CONTENT_EXTRACTOR_ROOT` and the expected extractor commit
set. It passed every assertion and re-ran the same-key replay. The CI job
repeats this path from a fresh GitHub checkout, pins the extractor commit, and
uploads `evidence.json`; the PR records the run URL and merge commit.

The workflow is separate and path-filtered, so it does not gate unrelated PRs.
Its cleanup step runs with `if: always()`, including after a failed run, so
hosted runners do not retain the stack.

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
