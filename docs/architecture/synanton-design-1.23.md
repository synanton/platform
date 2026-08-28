# Synanton Platform - Architecture (v1.23)

> **Document type:** Definitive engineering reference
> **Version:** 1.23
> **Date:** 2026-08-28
> **Status:** Approved (implementation in progress)
> **Audience:** Architects, module owners, security engineers, SREs
> **Related docs:** [synanton-design-1.22.md](./synanton-design-1.22.md), [classification-aware-search implementation plan](../implementation/classification-aware-search/INDEX.md), [classification-aware semantic search demo](../demos/classification-aware-semantic-search-demo.md)

## 1. Motivation

v1.22 introduced structured extraction and semantic chunking, but the platform’s security model remains **resource‑centric** (`SPACE | PROJECT | FOLDER | DOCUMENT`). It cannot express **sub‑document** sensitivity — a single PDF containing identity (RESTRICTED), personal  contact (PERSONAL), and financial compensation (FINANCIAL) data must be  searchable by different roles without exposing the restricted spans.

The design‑level review (`security-review-findings.md`) identifies six enforcement gaps:

| #    | Gap                                                          | Consequence                                                  |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1    | `acl_grants` granularity stops at `DOCUMENT`                 | Cannot grant HR access to PERSONAL sections while denying FINANCIAL |
| 2    | Chunk model has no `classification` field                    | No filterable attribute exists                               |
| 3    | Cuckoo ACL pre‑filter is `HIGH_SECURITY`‑only                | `STANDARD` tenants rely on post‑filter, which leaks term statistics and hit counts |
| 4    | Restricted spans are written to **seven stores** before any gate | MinIO, Cassandra chunks, analysis cache, embedding cache, Kafka (≥30d  retention), search index, graph, synthesis cache, anomaly topic |
| 5    | `PII redaction` is named but never specified                 | §6 step 5 lists it as optional — no detector, policy, or contract |
| 6    | v1.21/v1.22 extraction contracts have no security surface    | The only mention is `Sanitization | Optional | redacted output` in the PDF PoC |

The platform therefore **cannot** guarantee that a restricted literal (e.g. SSN) is never stored, never  indexed, never embedded, and never leaked through query‑side channels.

This proposal closes those gaps by introducing a **classification‑aware search** model that operates at **chunk granularity**, enforces **compile‑time filtering**, and provides a **fail‑closed** default for unlabelled content.

## 2. Summary of Changes

| #    | Change                                                       | Home in v1.23       |
| ---- | ------------------------------------------------------------ | ------------------- |
| 1    | **Classification model** — `class_grants` table (USER/GROUP/ROLE → class), chunk field `classification[]`, propagation over §11 outbox | §3.1, §25           |
| 2    | **Deterministic detector stage** in `synflux` over structured `elements` (SSN, phone, address, table‑header rules) | §3.2, §17           |
| 3    | **Compile‑time class filtering** — `AclInjector` adds `Must(class ∈ caller_classes)` for **all** tiers, not just HIGH_SECURITY | §3.3, §23, §40      |
| 4    | **Masking / quarantine** — restricted spans become `[REDACTED:CLASS]` before Cassandra commit; `manifest.state = QUARANTINED` on detector error | §3.4, §17           |
| 5    | **Propagation to all stores** — embedding cache keyed by `(tenant, class)`; graph entities carry class; synthesis cache `acl_mask` gains class set | §3.5, §18, §21, §23 |
| 6    | **Query‑side sanitisation** — suppress classified terms from suggest/autocomplete; strip raw query text from `synanton_anomaly` when it matches a restricted pattern | §3.6, §14, §45      |
| 7    | **Remediation** — `ReindexAfterPolicyChangeWorkflow` for relabelling; GDPR cascade (§10) for leak repair | §3.7, §10, §27      |
| 8    | **Observability + alerts** — new metrics and `RestrictedContentDetectedInIndex` (page) | §3.8, §45           |
| 9    | **CI gate** — `test:security` tier with negative corpus; restricted literals appear in **no** store | §3.9, §48a          |

## 3. Detailed Design

### 3.1 Classification Model & `class_grants`

**Module:** `topology` (§25)

**Goal:** Express role‑to‑class entitlements as a separate axis from resource ACLs.

**Implementation:**

Add `class_grants` table:

sql

```
CREATE TABLE class_grants (            -- topology, new
  grant_id   UUID PRIMARY KEY,
  org_id     UUID NOT NULL,
  subject_id UUID NOT NULL,
  subject_type TEXT NOT NULL,          -- USER | GROUP | ROLE
  class      TEXT NOT NULL,            -- PERSONAL | FINANCIAL | RESTRICTED | PUBLIC
  permission TEXT NOT NULL,            -- SEARCH | VIEW
  created_at TIMESTAMPTZ NOT NULL
);
```



Effective visibility = `resource_acl ∧ class_grants`. Least privilege holds by construction: `PAYROLL` sees `FINANCIAL` and *not* `PERSONAL`. Propagation reuses the §11 outbox + two‑phase ack path; revocation reuses the O(1) Cuckoo delete.

**Chunk field:**

Extend the v1.22 chunk schema:

json

```
{
  "chunk_id": "…",
  "section_path": ["3. GPU Execution Plane", "3.1 GPU Gateway"],
  "classification": ["FINANCIAL"],      // new, repeated
  "page_start": 3,
  "page_end": 3,
  "source_elements": ["elem_42", "elem_43"]
}
```



**Configuration:**

yaml

```
topology:
  class_grants:
    propagation_timeout_ms: 5000
    high_security_ack_deadline_ms: 50   # reuses §11
```



### 3.2 Deterministic Detector Stage

**Module:** `synflux` (§17)

**Goal:** Detect restricted spans *before* any write to Cassandra, Kafka, or the search index.

**Implementation:**

A new `ClassificationDetector` stage runs **after** parsing and **before** the Cassandra commit (preserving the §6 cache‑before‑bus invariant). It operates over the structured `elements` from v1.21 extraction.

| Detector      | Pattern                                       | Action       |
| ------------- | --------------------------------------------- | ------------ |
| SSN           | `\b\d{3}-\d{2}-\d{4}\b` + Luhn‑check          | `RESTRICTED` |
| Phone (US)    | `\b\d{3}-\d{3}-\d{4}\b`                       | `PERSONAL`   |
| Address       | regex + gazetteer                             | `PERSONAL`   |
| Table headers | `"Gross income"`, `"Federal tax"`, `"Salary"` | `FINANCIAL`  |

Detectors are **deterministic**, **auditable**, and **GPU‑free**. Misses are caught by `synreview` (§27a) human adjudication for low‑confidence spans.

**Policy per class:**

yaml

```
synflux:
  classification:
    enabled: true
    detectors: [ssn, phone, address, table_header]
    policy:
      RESTRICTED: MASK           # MASK | DROP | QUARANTINE | ROLE:security_officer
      PERSONAL:   MASK
      FINANCIAL:  MASK
    fail_mode: quarantine        # on detector error, quarantine the document
    quarantine_state: QUARANTINED
```



**Span masking:**

Before Cassandra commit, restricted spans are replaced:

diff

```
- "SSN: 000-00-0000"
+ "SSN: [REDACTED:SSN]"
```



The original span is **never** written to any store in the table below.

**Store reachability (pre‑gate):**

| Store                           | Reached at        | Post‑gate                 |
| ------------------------------- | ----------------- | ------------------------- |
| MinIO / `synvault` raw bytes    | step 2            | **out of scope** — see §5 |
| `ingestion_cache_chunks`        | step 3            | masked                    |
| `ingestion_cache_analysis`      | step 5b           | masked                    |
| `embedding_content_cache`       | step 6            | masked                    |
| `synflux_enriched_chunks` Kafka | step 8            | masked                    |
| `synquest` BM25 + HNSW          | step 9            | masked                    |
| `relix` graph                   | step 9            | masked                    |
| synthesis cache                 | query step 4      | filtered                  |
| `synanton_anomaly` topic        | query steps 13–14 | stripped                  |

### 3.3 Compile‑Time Class Filtering

**Module:** `gateway` (§23), `planner` (§22)

**Goal:** Class filtering is applied **before** BM25/IDF statistics are computed, not as a post‑filter.

**Implementation:**

`AclInjector` (formerly ACL‑only) now adds **both** resource ACL clauses **and** class clauses at compile time:

java

```
// Before: Must(org_id=acme, space_id=finance)
// After:  Must(org_id=acme, space_id=finance, class IN ('FINANCIAL', 'PUBLIC'))
```



This reaches:

- BM25 term statistics (the term `SSN` is never counted for unauthorised roles)
- HNSW pre‑filter (vectors of restricted chunks are never considered)
- Cuckoo ACL filter (extended with class dimension for HIGH_SECURITY)

**Mandatory for all tiers.** The Cuckoo pre‑filter is no longer `HIGH_SECURITY`‑only— class filtering is enforced for `STANDARD` tenants too.

**Configuration:**

yaml

```
synquest:
  classification:
    filter:
      enabled: true
      fail_closed: true          # if class field missing, treat as RESTRICTED

gateway:
  classification:
    enforce: true
    default_class: RESTRICTED    # unlabelled chunks → most restrictive
```



### 3.4 Quarantine / Masking

**Module:** `synflux` (§17)

**Goal:** Provide a fail‑closed path when a detector errors or confidence is low.

**Implementation:**

| Outcome                                | Action                                                       |
| -------------------------------------- | ------------------------------------------------------------ |
| Detector returns high‑confidence class | Apply policy (`MASK` / `DROP` / `QUARANTINE`)                |
| Detector error                         | `manifest.state = QUARANTINED`; no chunk published; alert fires |
| Confidence below threshold             | Route to `synreview` (§27a) for human adjudication; document remains `PENDING_REVIEW` |

**Quarantine manifest state:**

sql

```
-- new state value
ALTER TABLE ingestion_cache.manifest
  ADD CONSTRAINT manifest_state_check
  CHECK (state IN ('ACQUIRED', 'PARSED', 'CHUNKED', 'ENRICHED', 'EMBEDDED',
                   'INDEXED', 'QUARANTINED', 'PENDING_REVIEW'));
```



### 3.5 Propagation to All Stores

**Embedding cache** (§18):

Key changes from `(tenant, chunk_text_hash)` to `(tenant, class, chunk_text_hash)`. Rationale: vectors of classified text are themselves classified  (inversion risk). Cross‑tenant sharing is disabled for any entry with a  non‑`PUBLIC` class.

**Graph entities** (§21):

Entities and edges derived from classified spans carry the class. Reuses `source_ref_count` plumbing (§10 step 5). Graph expansion (§8/§21) filters by class before traversal.

**Synthesis cache** (§23):

`acl_mask` gains a class set:

json

```
{
  "acl_mask": {
    "org_id": "acme",
    "class_set": ["FINANCIAL", "PUBLIC"]
  }
}
```



Cross‑tenant reuse is disabled for any entry whose sources carry a non‑`PUBLIC` class.

**LLM context assembly** (§23):

Class filtering happens **before** prompt assembly. GPU‑degraded and cache‑hit paths must not skip it.

### 3.6 Query‑Side Sanitisation

**Channels:**

| Channel                  | Action                                                       |
| ------------------------ | ------------------------------------------------------------ |
| Suggest / autocomplete   | Suppress terms from restricted classes                       |
| `synanton_anomaly` topic | Strip raw query text when it matches a restricted pattern    |
| `execution_trace`        | Omit hit counts and per‑class statistics for restricted classes |
| Highlight snippets       | Redact restricted spans before rendering                     |

**Implementation:** Reuse the `ClassificationDetector` patterns at query time.

### 3.7 Remediation

**Leak repair:** The existing GDPR erasure cascade (§10, p99 ≤ 45 s) removes content from all planes. A new `ReindexAfterPolicyChangeWorkflow` (Temporal, `control-plane`) handles relabelling when a classification policy changes.

**Workflow steps:**

1. Read `manifest` rows affected by policy change.
2. Re‑run `ClassificationDetector` with new policy.
3. Re‑index chunks with updated `classification[]`.
4. Update `synquest` and `relix`.

### 3.8 Observability

**New metrics:**

| Metric                                 | Labels            | Description                                       |
| -------------------------------------- | ----------------- | ------------------------------------------------- |
| `synflux_classification_spans_total`   | `class`, `action` | Spans detected and action taken                   |
| `synflux_documents_quarantined_total`  | `reason`          | Documents quarantined (detector error, policy)    |
| `synquest_class_filter_rejected_total` | `class`           | Chunks rejected at query time due to class filter |
| `gateway_class_denied_total`           | `class`, `role`   | Query‑time denials by role                        |

**New alerts:**

| Alert                               | Condition                                       | Severity |
| ----------------------------------- | ----------------------------------------------- | -------- |
| `RestrictedContentDetectedInIndex`  | Any restricted literal found in index post‑gate | Page     |
| `ClassificationDetectorFailureRate` | Detector error rate > 1% over 5 min             | Warning  |

### 3.9 CI Gate: `test:security`

**Tier:** `test:security` (new, runs on every PR that touches classification paths)

**Corpus:** `demo-data/documents/restricted/` (see Deliverable 4)

**Assertions:**

bash

```
# SSN literal appears in NO store
grep -r "000-00-0000" cassandra/ chunks/ kafka-payloads/ index-terms/ && exit 1

# Chunk classification field is present
grep '"classification":\s*\["RESTRICTED"\]' cassandra/chunks/

# Class filter rejects unauthorised role
curl -H "X-Synanton-Role: hr" /search?q=SSN | jq '.hits | length' == 0
```



**Pass criteria:** Restricted literals appear in **zero** stores — index terms, Cassandra rows, Kafka payloads, embedding cache, graph entities, synthesis cache.

## 4. Impact on Existing Modules / Sections

| Section                      | Changes                                                      |
| ---------------------------- | ------------------------------------------------------------ |
| **§6 Ingestion Flow**        | New `ClassificationDetector` stage before Cassandra commit   |
| **§10 GDPR Erasure Cascade** | Cascade now includes class‑aware cleanup; `ReindexAfterPolicyChangeWorkflow` added |
| **§17 `synflux`**            | New detector stage, `QUARANTINED` state, `classification` field on chunks |
| **§18 `ingestion-cache`**    | Embedding cache keyed by `(tenant, class)`; cross‑tenant sharing disabled for non‑PUBLIC |
| **§20 `synquest`**           | Class filter mandatory for all tiers; Cuckoo filter extended |
| **§21 `relix`**              | Entities/edges carry class; graph expansion filters by class |
| **§23 `gateway`**            | Compile‑time class injection; synthesis cache `acl_mask` gains class set |
| **§25 `topology`**           | New `class_grants` table                                     |
| **§27 `control-plane`**      | New `ReindexAfterPolicyChangeWorkflow`                       |
| **§40 Identity, ACL**        | Class filtering added to three‑layer model                   |
| **§45 Observability**        | New metrics and alerts                                       |
| **§48a Testing Discipline**  | New `test:security` tier                                     |

## 5. Backward Compatibility & Upgrade Path

**No breaking changes.** All new features are opt‑in:

- `synflux.classification.enabled = false` (default) — ingestion behaviour unchanged
- `synquest.classification.filter.enabled = true` — safe when field is absent (treats as `PUBLIC` during migration; `fail_closed` can be toggled)
- `gateway.classification.enforce = false` (default) — existing queries unaffected

**Rolling upgrade:**

1. Deploy v1.23 with all flags `false`.
2. Enable detectors on a canary tenant.
3. Seed `class_grants` for that tenant.
4. Verify `test:security` passes.
5. Roll out to all tenants.

**Residual risk:** The original PDF in `synvault` retains the SSN. This is **intentional** — raw‑object storage is outside the search system and is protected by separate encryption + `content:read` grants. For deployments that require sanitisation at rest, a pre‑ingest sanitisation pipeline can be configured (out of scope for v1.23).

## 6. Implementation Plan

| Week  | Phase     | Tasks                                                        |
| ----- | --------- | ------------------------------------------------------------ |
| 1–2   | **SEC‑1** | `class_grants` table + propagation (§11); extend chunk schema with `classification[]` |
| 3–4   | **SEC‑2** | Deterministic detectors in `synflux`; policy config; `QUARANTINED` state |
| 5–6   | **SEC‑3** | Compile‑time class filtering in `gateway`/`planner`; extend Cuckoo filter |
| 7–8   | **SEC‑4** | Masking implementation; `test:security` corpus + CI gate     |
| 9–10  | **SEC‑5** | Embedding cache key change; graph entity class propagation; synthesis cache `acl_mask` |
| 11–12 | **SEC‑6** | Physical separation for regulated tenants (optional, per‑tenant opt‑in) |

## 7. Open Questions

1. **Detector confidence threshold.** What is the acceptable false‑positive rate for `RESTRICTED` detection? Proposal: start with 0.1% FP, tune with `synreview` feedback.
2. **Cross‑tenant sharing.** Should `PUBLIC`‑class chunks remain shareable across tenants? Yes — retains v1.22 behaviour for non‑sensitive content.
3. **Original PDF retention.** Should the platform offer a pre‑ingest sanitisation pipeline? Out of scope for v1.23; documented as a deployment‑time choice.

## 8. Conclusion

v1.23 introduces a **classification‑aware semantic search** model that closes the sub‑document security gap identified in the design review. By adding a `classification[]` field to chunks, deterministic detectors at ingest, compile‑time class  filtering, and a fail‑closed default for unlabelled content, the  platform can guarantee that restricted literals are **never stored, never indexed, never embedded, and never leaked** through query‑side channels.

The design is **additive**, **non‑breaking**, and **rollback‑safe**. All new features are opt‑in, with safe defaults that preserve v1.22 behaviour.

**Next steps:**

- Continue SEC‑2 through SEC‑6 per [implementation plan](../implementation/classification-aware-search/INDEX.md).
- Fold approved sections into the merged design baseline when v1.23 reaches GA.