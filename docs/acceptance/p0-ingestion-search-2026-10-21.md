# P0 acceptance — ingestion → search → provenance (2026-10-21)

**Result:** **P0 passes; the platform five-check is red in the current revision.**
This record follows the review's sequence: confirm the platform acceptance first,
then prove one real book through the supported `/v1` contract, record exact
payloads, and keep extraction conformance and `#338` separate.

P0 acceptance does **not** require extraction-gateway on the path. Whether it is
on the path is determined by the C0 check below and recorded as a known
deviation/open item, not assumed from P0.

## Revision and deployment

| Item | Value |
|---|---|
| platform | `ec3d414` (working tree clean before this record) |
| lucentrix | `0e9cb02` on `feat/19-sink-v1-ingestion-runs` (PR #21) |
| ui | `e3f1dcd` |
| synflux image | `local-registry:5000/synanton/synflux:slice-20261021` (thin rebuild from current source) |
| synquest image | `local-registry:5000/synanton/synquest:slice-20261020` (aligned after C0; P0 captured with `slice-20261019`) |
| extraction-gateway image | `local-registry:5000/synanton/extraction-gateway:slice-20261020` (aligned after C0; P0 captured with `slice-20261019`) |
| endpoints | synflux `http://192.168.10.32:30090`, synquest `http://192.168.10.32:30083` |
| tenant | `demo` (P0); `slice-a`/`slice-b` (platform five-check) |

The sink migration is PR synanton/lucentrix#21 (`0e9cb02`); the P0 run used
the same working tree and is reproducible from that branch. The platform
five-check ran against the current synflux image. The gateway and synquest
images were aligned from `slice-20261019` to `slice-20261020` during the C0
investigation; the P0 search/provenance were re-checked on the aligned image
after a projection rebuild (45 chunks; `configuration_generation=20`), with the
same top hit and provenance.

## 1. Platform five-check (D1') — **FAIL at Criterion 1**

Command: `platform/deployments/homelab/scripts/run-slice.sh` (k8s profile).

```
=== Criterion 1 — ingest creates an immutable version ===
  PASS source created
  PASS run submitted — run_id a1242832-3aea-3d82-b015-ce397755d7c6
  PASS operation SUCCEEDED
  FAIL outcome FULL (expected 'FULL', got 'PARTIAL')
```

The script exits on the first failure; criteria 2–5 (provenance, restart
recovery without duplicate versions, search, cross-tenant) were **not executed**
in this run. The five-check is therefore **not green** in the current revision.

Recorded operation (`slice-a`, run `a1242832-3aea-3d82-b015-ce397755d7c6`):

```json
{
  "operation_id": "63b7a331-5058-4d85-9f15-2f2ab5c69982",
  "type": "INGESTION",
  "status": "SUCCEEDED",
  "outcome": "PARTIAL",
  "requested_by": { "principal_id": "slice-operator" },
  "tenant_id": "slice-a",
  "details": {
    "feature_states": {
      "text": "FEATURE_APPLIED",
      "tables": "FEATURE_UNSUPPORTED",
      "transcription": "FEATURE_NOT_REQUESTED",
      "embeddedImages": "FEATURE_UNSUPPORTED",
      "layout": "FEATURE_NOT_APPLICABLE",
      "ocr": "FEATURE_NOT_APPLICABLE"
    },
    "payload_descriptor": {
      "schema_id": "synanton.extraction.document",
      "schema_version": "1.0",
      "processor_id": "text-adapter",
      "processor_version": "1.0"
    }
  },
  "error": null
}
```

Cause: the fixture is Markdown; the extraction path's `text-adapter` reports
`tables`/`embeddedImages` as `FEATURE_UNSUPPORTED`, so the run is `PARTIAL`. The
acceptance script still expects `FULL`, an expectation written before the
extraction stage was on the path. This is an **acceptance-expectation mismatch**,
not a P0 data failure. Resolve by either (a) allowing/recording `PARTIAL` for
text-only fixtures or (b) using a PDF fixture for the five-check. Do not treat
the platform five-check as passing until this is resolved and criteria 2–5 run.

Also recorded: `vertical-slice.sh` prints
`cd: deployments/docker: No such file or directory` (line 66) when invoked via
`run-slice.sh`; the k8s criteria still run, but the script's working-directory
assumption is wrong.

## 2. P0 — one real PDF through Lucentrix → `/v1` — **PASS**

Route: filesystem crawler → `SynantonSinkPlugin` → `POST /v1/sources` then
multipart `POST /v1/ingestion-runs` (bearer token; no `X-Tenant`; no
`Idempotency-Key` header). The source was created by the sink on the first
upload.

| Field | Value |
|---|---|
| source | `p0-books` (upload; `upload://p0`) |
| resource | `refresher-algebra-calculus.pdf` |
| ingestion run | `9da964b3-a927-3a40-9bcb-5745a0d361ad` (`COMPLETED`) |
| operation | `f16232ff-ce62-48e4-bb82-05cc9eaa3e5d` (`SUCCEEDED`, `FULL`) |
| source version | `f3c1cc0f-cb60-5602-9cc3-4888ec55c311` |
| content ref | `bdf91543-1dd9-5509-89ab-5f559cd057ca` |
| content SHA-256 | `41fefaabe8afc9e7efa3185f330daa5099fb5faa9036fadf0be09aa771306141` |
| processing run (chunk) | `25cda9f4-246c-3da8-bf3e-f876d550c5b6` |

Exact operation payload:

```json
{
  "operation_id": "f16232ff-ce62-48e4-bb82-05cc9eaa3e5d",
  "type": "INGESTION",
  "status": "SUCCEEDED",
  "requested_by": { "principal_id": "alice" },
  "tenant_id": "demo",
  "progress": { "completed": 1, "total": 1 },
  "outcome": "FULL",
  "details": {
    "feature_states": {
      "text": "FEATURE_APPLIED",
      "tables": "FEATURE_APPLIED",
      "ocr": "FEATURE_NOT_REQUESTED",
      "embeddedImages": "FEATURE_NOT_APPLICABLE",
      "sceneAnalysis": "FEATURE_NOT_REQUESTED",
      "transcription": "FEATURE_NOT_APPLICABLE",
      "layout": "FEATURE_APPLIED"
    },
    "payload_descriptor": {
      "schema_id": "synanton.extraction.document",
      "schema_version": "1.0",
      "processor_id": "pdf-adapter",
      "processor_version": "1.0"
    }
  },
  "error": null
}
```

### Search (exact top hit and metadata)

`POST /v1/search {"query":"linear algebra and calculus"}` → `200`, 20 results,
`strategy=bm25`, `configuration_generation=16` on the P0 revision
(`slice-20261019`; 20 after the aligned-image rebuild, with the same top hit and
provenance).

```json
{
  "score": 0.01639344262295082,
  "object_id": "bdf91543-1dd9-5509-89ab-5f559cd057ca:4",
  "title": "Matrix properties",
  "snippet": "VIP Refresher: Linear Algebra and Calculus > Matrix properties\n\nFor x ∈ V , the most commonly used norms are summed up in the table below:",
  "provenance_ref": "/v1/knowledge/bdf91543-1dd9-5509-89ab-5f559cd057ca:4/provenance",
  "source_version_ids": ["f3c1cc0f-cb60-5602-9cc3-4888ec55c311"]
}
```

The run is **BM25 only**. `synflux` has
`SYNFLUX_PIPELINE_EMBEDDING_ENABLED=false` and `synquest` has
`SYNQUEST_EMBEDDING_REQUIRED=false`; the vector leg (D2) is not present. The P0
review does not require it; record it as an open phase.

### Provenance (exact)

`GET /v1/knowledge/bdf91543-1dd9-5509-89ab-5f559cd057ca:4/provenance` → `200`:

```json
{
  "object_id": "bdf91543-1dd9-5509-89ab-5f559cd057ca:4",
  "content_ref_id": "bdf91543-1dd9-5509-89ab-5f559cd057ca",
  "source_version_ids": ["f3c1cc0f-cb60-5602-9cc3-4888ec55c311"],
  "content_cache_reference": "v1:tenant_id=demo&artifact_id=657627d6-24a8-4e32-b1b6-2cc7892c7f9f&digest=sha256%3A41fefaabe8afc9e7efa3185f330daa5099fb5faa9036fadf0be09aa771306141&generation=1&serializer_version=1",
  "source_uri": "upload://p0-books/refresher-algebra-calculus.pdf",
  "source_sha256": "41fefaabe8afc9e7efa3185f330daa5099fb5faa9036fadf0be09aa771306141",
  "chunk_sha256": "b6bb4b8413a29067272d400b453c137a721e75198c90241118e8a88b81cf94eb",
  "processing_run_id": "25cda9f4-246c-3da8-bf3e-f876d550c5b6"
}
```

### Page ranges — canonical rows present, public contract absent (`#338`)

Canonical `chunks_payload` rows for the content ref:

```
chunk 0: pages 1–1     chunk 4: pages 2–2 (the top hit)
chunk 1: pages 1–2     chunk 5: pages 2–2
chunk 2: pages 2–2     chunk 6: pages 2–2
chunk 3: pages 2–2     chunk 7: pages 2–2
```

The public provenance payload above has no `page_start`/`page_end`. Per the
review, this is **not** worked around: `#338` is the open contract question. The
canonical data carries the page ranges; the public `/v1` contract does not
expose them. If `#338` resolves by extending the contract, the UI can render
page 2 for this hit; until then it renders absence (B-pre).

### Chunk-count reproducibility

- The P0 PDF (`refresher-algebra-calculus.pdf`, SHA-256 `41fefaab…`) has **8**
  canonical chunks (ordinals 0–7; pages 1–2).
- The synquest tenant-`demo` projection reports **45** chunks after the aligned
  rebuild (`synflux slice-20261021`, `synquest slice-20261020`,
  `configuration_generation=20`). That is the **tenant-wide** projection, not
  the P0 PDF's chunk count; it includes the other ingested content (e.g. the
  6-page `gw-check` upload).
- The earlier local-stack run recorded **24** chunks for **lesson-1.pdf** (a
  different PDF and stack), not the P0 PDF. It is historical and is **not** a
  regression baseline.
- The current per-PDF count (8) and tenant projection count (45) are
  authoritative for the pinned revision/images; 24 vs 45 is not a
  same-document comparison.

## 3. Tenant-B negative — **PASS**

Tenant-B token (`tenant_id=tenant-b`), same query, body says
`"tenant_id":"demo"` (must be ignored):

```json
{
  "rebuild": { "status": "done", "tenant_id": "tenant-b", "chunk_count": 0, "strategy": "bm25" },
  "search": { "results": [], "metadata": { "strategy": "bm25", "configuration_generation": 4 } },
  "provenance_status": 404
}
```

Before the empty projection was built, tenant-B search returned `503`
(fail-closed, not a leak). This is **correct fail-closed behavior**; a 503 while
a tenant's projection is absent must not be "fixed" into an empty 200. After the
empty projection it returns `200` with zero results. The isolation evidence is
the empty tenant-B projection plus the `404` provenance.

The body tenant was **ignored**, not rejected. `{"tenant_id":"demo"}` with the
tenant-B token still returned tenant-B's (empty) results. ADR 0007 expects
`400 TENANT_IN_REQUEST_BODY`; the platform does not enforce that yet. This is a
D5 adapter conformance gap and a reason the tenant-B result must not be read as
proof of the body-tenant rule. The UI client already rejects a body tenant
client-side; the platform-side rule is still narrow. Tracked in #347.

The `X-Tenant-ID` header rejection is **not** implemented in synquest
(the header is ignored); that is the D5 adapter requirement, not part of P0,
and is included in #347.

## 4. Idempotency finding — platform 500 on duplicate SHA key

The first Lucentrix sink attempt used the file SHA-256 as `Idempotency-Key`.
The platform returned `500 Internal Server Error`:

```json
{"timestamp":"2026-10-21T05:38:31.628+00:00","status":500,"error":"Internal Server Error","path":"/v1/ingestion-runs"}
```

A unique key (`p0-test-<uuid>`) and no key both return `202`; the file SHA key
returns `500` (duplicate/failed idempotency path). The sink now sends **no**
`Idempotency-Key` and relies on content-cache dedupe, per the plan. A re-crawl
of an already-ingested file returns `UNCHANGED`
(run `7285465f-04cc-339f-b8cc-b473fe02923e`), confirming the dedupe.

The platform should return the existing operation or `409` on a duplicate key,
never `500`.

## 5. C0 — extraction conformance (separate)

- **PDF P0 — gateway called (log-confirmed).** The gateway log at 05:41:19
  shows `DocumentProcessor` for the 2-page PDF
  (`Processing 2 pages with 1 threads`), matching the P0 operation. The
  extraction-gateway is on the PDF path.
- **Markdown fixture — in-process.** There is no gateway log for the fixture at
  05:44. Its `text-adapter` result (tables/embeddedImages unsupported →
  `PARTIAL`) is the in-process fallback. That acceptance mismatch is #345, not
  a gateway call.
- **Durable record absent on both images (R2).** `extraction_operations=0`,
  `extraction_results=0`, `extraction_idempotency=0` after the P0 and after a
  new `gw-check` upload using the aligned `slice-20261020` gateway (6-page PDF;
  gateway logs 05:53:13–14; run `401d0285-00e3-3de4-bb41-8e20cf76b639`,
  operation `8f82932c-8099-4d46-a715-75f0c8320fb7`). The newer image also writes
  nothing, so this is **not** stale-image skew. Tracked in #346.

**C0 verdict:** the extraction-gateway is on the PDF path (log-confirmed); the
Markdown fixture is in-process; the durable extraction operation record is
absent on both images (R2) and is tracked in #346. This is recorded separately
from P0, as required.

## Findings and next reviewable state

1. **Platform five-check red** at Criterion 1 (Markdown `PARTIAL` vs expected
   `FULL`); criteria 2–5 not executed. The fixture is in-process; the acceptance
   expectation and the `cd` bug are tracked in #345.
2. **Extraction gateway processes but does not persist (R2).** The gateway is on
   the PDF path (log-confirmed), but writes no `extraction_operations`/results on
   either `slice-20261019` or the aligned `slice-20261020`. Tracked in #346.
3. **Duplicate `Idempotency-Key` → 500.** Platform fix; sink already avoids it.
   Investigated: the SHA key maps to a successful operation in
   `ingestion_cache.operation_idempotency`; the replay across a different source
   produces the 500. This is the platform ingestion path, independent of #346.
   Tracked in #344.
4. **Body tenant ignored, not rejected** (#347). ADR 0007 expects
   `400 TENANT_IN_REQUEST_BODY`; the platform returned 200 with the claim's
   tenant. The UI does not send it; other clients need the platform-side rule.
5. **`#338` page ranges** remain a public-contract gap; canonical rows carry
   them; UI renders absence until the contract changes. The extractor path
   already supplies them, so this is a response-shape change, not an
   integration gap.
6. **D2 vector leg** is absent (BM25 only); not a P0 requirement, but the
   demo's dual-index claim is not yet true.
7. **D5/D6** (login/CORS/tenant-header rejection, real UI transport) are not
   implemented. `#340`/`#341`/`#339` remain.

**P0 is the gate that is now proven.** The platform five-check is the next
red item; the UI/API integration follows it, not the other way around.
