# Synanton Platform - Architecture (v1.23)

> **Document type:** Definitive engineering reference
> **Version:** 1.23
> **Date:** 2026-08-28
> **Status:** Approved (implementation in progress)
> **Audience:** Architects, module owners, security engineers, SREs
> **Related docs:** [synanton-design-1.22.md](./synanton-design-1.22.md), [classification-aware-search implementation plan](../implementation/classification-aware-search/INDEX.md), [classification-aware semantic search demo](../demos/classification-aware-semantic-search-demo.md)
> **Revision note (2026-08-29):** §3.2–§3.5 and §3.9 revised from whole-chunk exclusion to a masking-outcome-driven **original/masked representation model** — see §3.2a, §3.3, §3.4.

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
| 3    | **Compile‑time representation selection** — `AclInjector` adds `Must(class ∈ caller_classes)` for **all** tiers, not just HIGH_SECURITY, and selects the `original` vs `masked` field/embedding/graph-tag per caller | §3.2a, §3.3, §23, §40 |
| 4    | **Masking / quarantine** — spans matching a class policy become `[REDACTED:CLASS]`; the chunk keeps a single representation if masking made no change, or gains an authorized-only `original` representation alongside the always-written `masked` one, unless the class's `store_original: false` policy suppresses the original entirely (e.g. SSN/`RESTRICTED`); `manifest.state = QUARANTINED` on detector error | §3.2a, §3.4, §17    |
| 5    | **Propagation to all stores** — embedding cache keyed by `(tenant, class, representation)`; graph entities/edges carry `classification` and `representation`; synthesis cache `acl_mask` gains class set | §3.5, §18, §21, §23 |
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
      RESTRICTED:
        action: MASK              # MASK | DROP | QUARANTINE | ROLE:security_officer
        store_original: false     # never persist the original — see §3.2a
      PERSONAL:
        action: MASK
        store_original: true      # authorized callers may retrieve the original
      FINANCIAL:
        action: MASK
        store_original: true
    fail_mode: quarantine        # on detector error, quarantine the document
    quarantine_state: QUARANTINED
```



Detector output (matched spans and classes) feeds the masking‑outcome decision in §3.2a, which determines whether a chunk ends up with one representation or two.

### 3.2a Masking Outcome & Representation Decision

**Module:** `synflux` (§17)

**Goal:** Decide, per chunk, whether masking produced a second, more sensitive representation — and whether that representation is allowed to exist in storage at all.

**Implementation:**

The masking stage always computes a masked form of the chunk's content and compares it against the original:

```
detector match
      │
      ▼
apply masking policy → masked_content
      │
      ▼
masked_content == original_content ?
      │                    │
     yes                   no
      │                    │
      ▼                    ▼
SINGLE                store_original for the
representation         matched class?
(everyone gets it)     │              │
                      true           false
                        │              │
                        ▼              ▼
                     DUAL          MASKED‑ONLY
                representation    (no original ever
                (original gated    stored, for anyone)
                 by class_grants)
```

The three outcomes:

| Outcome         | When                                                                            | What is stored                                                                          | Who gets what                                                                                    |
| ---------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| **Single**       | Masking makes no change (no literal span matched in *this* chunk)                | One `content` field per store                                                              | Everyone with resource access — `classification[]` is retained for provenance/audit but does not gate this chunk's retrieval |
| **Dual**         | Masking changes the content **and** the matched class's `store_original: true`   | Two fields per store: `content_masked` (always) and `content_original` (class‑grant‑gated) | Class‑authorized callers get `content_original`; everyone else gets `content_masked`                |
| **Masked‑only**  | Masking changes the content **and** the matched class's `store_original: false` (e.g. `RESTRICTED`/SSN) | Only `content_masked` — the original is never computed for storage in any store            | Everyone, including class‑authorized callers — a hard "restricted for all" configuration             |

This subsumes the earlier, hard‑coded "original span never written to any store" rule as one setting (`store_original: false`) of a single mechanism, rather than a special case. Classification and masking are still evaluated **per chunk**: the same `FINANCIAL` class can produce a Single outcome for a chunk that only mentions "the executive compensation program" in prose, and a Dual outcome for the chunk containing the actual salary table.

**Span masking example (Dual outcome):**

diff

```
- "Salary: €180,000"
+ "Salary: [REDACTED:FINANCIAL]"
```



Both forms are available downstream; which one a given store and caller sees is governed by §3.3.

**Span masking example (Masked‑only outcome, `store_original: false`):**

diff

```
- "SSN: 000-00-0000"
+ "SSN: [REDACTED:SSN]"
```



`"000-00-0000"` is never computed for storage — not even for a caller holding `RESTRICTED` class grants. There is no "original" artefact to gate.

**Store reachability (pre‑gate):**

| Store                           | Reached at        | Single outcome | Dual outcome                                              | Masked‑only outcome         |
| -------------------------------- | ----------------- | --------------- | ------------------------------------------------------------ | ----------------------------- |
| MinIO / `synvault` raw bytes    | step 2            | n/a — out of scope, see §5 | n/a — out of scope, see §5                        | n/a — out of scope, see §5    |
| `ingestion_cache_chunks`        | step 3            | one field       | `content_masked` + `content_original`                        | `content_masked` only         |
| `ingestion_cache_analysis`      | step 5b           | one field       | `content_masked` + `content_original`                        | `content_masked` only         |
| `embedding_content_cache`       | step 6            | one embedding   | `embedding_masked` + `embedding_original`                     | `embedding_masked` only       |
| `synflux_enriched_chunks` Kafka | step 8            | one field       | both fields                                                   | `content_masked` only         |
| `synquest` BM25 + HNSW          | step 9            | one field       | both fields, representation selected at query time (§3.3)     | `content_masked` only         |
| `relix` graph                   | step 9            | untagged        | entities/edges tagged `representation`                        | `content_masked`‑derived only |
| synthesis cache                 | query step 4      | one entry       | representation‑aware, filtered by caller                      | masked entry only             |
| `synanton_anomaly` topic        | query steps 13–14 | passthrough     | stripped when it would reveal `content_original`              | stripped                      |

### 3.3 Compile‑Time Representation Selection

**Module:** `gateway` (§23), `planner` (§22)

**Goal:** For chunks with a Dual representation (§3.2a), select `original` vs `masked` **before** BM25/IDF statistics are computed or HNSW candidates are gathered — not as a post‑filter, and not as whole‑chunk exclusion.

**Implementation:**

`AclInjector` (formerly ACL‑only) now adds resource ACL clauses **and** a representation clause at compile time, derived from the caller's `class_grants`:

java

```
// Before: Must(org_id=acme, space_id=finance)
// After:  Must(org_id=acme, space_id=finance, class IN ('FINANCIAL', 'PUBLIC'))
//         → representation = ORIGINAL for chunks classified FINANCIAL, MASKED otherwise
```



Concretely, for a Dual‑representation chunk classified `FINANCIAL`:

- Caller holds a `FINANCIAL` class grant → query targets `content_original` / `embedding_original` / `representation=original` graph edges.
- Caller does not hold a `FINANCIAL` class grant → query targets `content_masked` / `embedding_masked` / `representation=masked` graph edges. The chunk is **not excluded** — the masked field is fully searchable, it simply never contains the sensitive literal.
- For Masked‑only chunks (`store_original: false`), every caller — regardless of class grants — resolves to `content_masked`, because no `original` field exists to select.
- For Single‑representation chunks there is only one field; representation selection is a no‑op and the chunk is reachable by everyone with resource access.

This reaches:

- BM25 term statistics (the literal `SSN` is never counted for any role; a masked literal such as a salary figure is never counted for unauthorised roles, though the surrounding masked text still is)
- HNSW pre‑filter (the `embedding_original` vector is never considered for unauthorised roles)
- Cuckoo ACL filter (extended with a class → representation dimension for HIGH_SECURITY)

**Mandatory for all tiers.** The Cuckoo pre‑filter is no longer `HIGH_SECURITY`‑only — representation selection is enforced for `STANDARD` tenants too.

**Configuration:**

yaml

```
synquest:
  classification:
    filter:
      enabled: true
      fail_closed: true          # if class field missing, treat as RESTRICTED (masked-only)

gateway:
  classification:
    enforce: true
    default_class: RESTRICTED    # unlabelled chunks → most restrictive representation
```



### 3.4 Quarantine / Masking

**Module:** `synflux` (§17)

**Goal:** Provide a fail‑closed path when a detector errors or confidence is low, on top of the §3.2a representation decision.

**Implementation:**

| Outcome                                                                                   | Action                                                                                                            |
| -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Detector returns high‑confidence class, masking unchanged                                    | Single representation (§3.2a)                                                                                     |
| Detector returns high‑confidence class, masking changed content, `store_original: true`      | Dual representation (§3.2a); action `MASK`                                                                        |
| Detector returns high‑confidence class, masking changed content, `store_original: false`     | Masked‑only representation (§3.2a); action `MASK`                                                                  |
| Policy action is `DROP` or `QUARANTINE` instead of `MASK`                                    | Chunk (or document) withheld entirely — more severe than masking, unaffected by the representation model above     |
| Detector error                                                                                | `manifest.state = QUARANTINED`; no chunk published; alert fires                                                    |
| Confidence below threshold                                                                    | Route to `synreview` (§27a) for human adjudication; document remains `PENDING_REVIEW`                             |

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

**Reverse index** (§20):

A Dual‑representation chunk is indexed as **two Lucene fields**: `content_masked` (always) and `content_original` (only when §3.2a resolves Dual). Both carry the same `classification[]` and `chunk_id`. The compiled query (§3.3) targets exactly one of the two fields per caller — never both, so an unauthorised caller's term statistics are computed solely against `content_masked`. Masked‑only chunks index `content_masked` alone; Single‑representation chunks index one plain `content` field as before.

**Embedding cache** (§18):

Key changes from `(tenant, chunk_text_hash)` to `(tenant, class, representation, chunk_text_hash)`, where `representation ∈ {single, masked, original}`. A Dual chunk produces **two cache entries and two embeddings** — `embedding_masked` and `embedding_original` — because the masked and original text differ and must not be assumed to embed to the same vector. Rationale for isolating the `original` entry: vectors of unmasked classified text are themselves classified (inversion risk). Cross‑tenant sharing is disabled for any entry keyed with a non‑`PUBLIC` class or an `original` representation; `masked` and `single` entries may still be shared cross‑tenant per the existing `PUBLIC` rule.

**Graph entities** (§21):

Entities and edges carry both `classification` and `representation`. For a Dual chunk, entity extraction runs once over `content_masked` and once over `content_original`; a fact that only exists in the original text (e.g. a salary value) produces an edge tagged `representation=original`, absent from the `masked` extraction. Graph traversal filters by `classification` **and** selects `representation` the same way §3.3 selects it for search — unauthorised callers traverse `representation=masked` edges only. Masked‑only chunks never produce `representation=original` edges. Reuses `source_ref_count` plumbing (§10 step 5).

**Synthesis cache** (§23):

`acl_mask` gains a class set and a representation marker:

json

```
{
  "acl_mask": {
    "org_id": "acme",
    "class_set": ["FINANCIAL", "PUBLIC"],
    "representation": "masked"
  }
}
```



A synthesis result that drew on any chunk's `content_original` is cached with `representation: "original"` and is only replayed to callers whose class grants would have resolved that same chunk to `original`. Cross‑tenant reuse is disabled for any entry whose sources carry a non‑`PUBLIC` class or an `original` representation.

**LLM context assembly** (§23):

Representation selection happens **before** prompt assembly, using the same per‑chunk decision as search (§3.3): the prompt receives `content_original` only for chunks the caller is class‑authorized for, `content_masked` otherwise. GPU‑degraded and cache‑hit paths must not skip it.

### 3.6 Query‑Side Sanitisation

**Channels:**

| Channel                  | Action                                                                                   |
| ------------------------ | ------------------------------------------------------------------------------------------- |
| Suggest / autocomplete   | Suppress terms that only occur in a `content_original` field the caller cannot resolve      |
| `synanton_anomaly` topic | Strip raw query text when it matches a masked‑only (e.g. SSN) pattern                       |
| `execution_trace`        | Omit hit counts and per‑class statistics for the `original` representation when unauthorised |
| Highlight snippets       | Render from the representation actually selected for the caller (§3.3) — never fall back to `content_original` for an unauthorised snippet |

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
# 1. Masked-only literal (store_original: false) appears in NO store, for ANY role
grep -r "000-00-0000" cassandra/ chunks/ kafka-payloads/ index-terms/ embedding-cache/ graph/ && exit 1

# 2. Chunk classification field is present
grep '"classification":\s*\["RESTRICTED"\]' cassandra/chunks/

# 3. Masked-only literal: exact-match search returns zero hits for every role, authorised or not
curl -H "X-Synanton-Role: hr" /search?q=SSN | jq '.hits | length' == 0

# 4. Dual-representation: unauthorised role sees the masked field, not zero hits and not the literal
curl -H "X-Synanton-Role: hr" /search?q="gross+income" | jq -r '.hits[0].snippet' | grep -q "REDACTED:FINANCIAL"

# 5. Dual-representation: authorised role sees the original value for the same chunk
curl -H "X-Synanton-Role: payroll" /search?q="gross+income" | jq -r '.hits[0].snippet' | grep -qv "REDACTED:FINANCIAL"

# 6. Single representation: unmodified chunk returns identical content to both roles
diff <(curl -H "X-Synanton-Role: hr" /search?q="compensation+program" | jq -r '.hits[0].snippet') \
     <(curl -H "X-Synanton-Role: bob" /search?q="compensation+program" | jq -r '.hits[0].snippet')
```



**Pass criteria:**

- `store_original: false` classes (e.g. `RESTRICTED`/SSN): the literal appears in **zero** stores, for every role — index terms, Cassandra rows, Kafka payloads, embedding cache, graph entities, synthesis cache.
- `store_original: true` classes with a Dual outcome (e.g. `FINANCIAL`, `PERSONAL`): an unauthorised role's search returns the chunk with `content_masked` (not zero hits, not the literal); an authorised role's search on the same chunk returns `content_original`.
- Single‑outcome chunks (masking made no change) return identical content to authorised and unauthorised roles.

## 4. Impact on Existing Modules / Sections

| Section                      | Changes                                                      |
| ---------------------------- | ------------------------------------------------------------ |
| **§6 Ingestion Flow**        | New `ClassificationDetector` stage before Cassandra commit   |
| **§10 GDPR Erasure Cascade** | Cascade now includes class‑aware cleanup; `ReindexAfterPolicyChangeWorkflow` added |
| **§17 `synflux`**            | New detector + masking‑outcome stage (§3.2a), `QUARANTINED` state, `classification` field on chunks, single/dual/masked‑only representation decision |
| **§18 `ingestion-cache`**    | Embedding cache keyed by `(tenant, class, representation)`; two embeddings for Dual chunks; cross‑tenant sharing disabled for non‑PUBLIC or `original` |
| **§20 `synquest`**           | Representation selection (§3.3) mandatory for all tiers, replacing whole‑chunk exclusion; two Lucene fields per Dual chunk; Cuckoo filter extended |
| **§21 `relix`**              | Entities/edges carry `classification` and `representation`; graph expansion selects representation before traversal |
| **§23 `gateway`**            | Compile‑time representation selection; synthesis cache `acl_mask` gains class set and `representation` marker |
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

**Residual risk:** The original PDF in `synvault` retains the SSN. This is **intentional** — raw‑object storage is outside the search system and is protected by separate encryption + `content:read` grants. For deployments that require sanitisation at rest, a pre‑ingest sanitisation pipeline can be configured (out of scope for v1.23). Within the search plane itself, the equivalent guarantee for `RESTRICTED`/SSN content is the `store_original: false` policy setting (§3.2a) — the literal is never computed for storage in the first place, independent of any caller's class grants.

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
4. **Contextual (non‑literal) classification.** Should a chunk that inherits a class from its section context — but contains no literal span that masking actually changed — still be treated as Single representation and shown to everyone (as specified here), or should some deployments be able to require Dual/Masked‑only behaviour purely from context‑level classification? Resolution for v1.23: Single representation, as decided in §3.2a; revisit if `synreview` feedback shows context‑only classification leaking sensitive inference.

## 8. Conclusion

v1.23 introduces a **classification‑aware semantic search** model that closes the sub‑document security gap identified in the design review. By adding a `classification[]` field to chunks, deterministic detectors at ingest, a masking‑outcome‑driven original/masked representation decision (§3.2a), compile‑time representation selection, and a fail‑closed default for unlabelled content, the platform guarantees that: content unaffected by masking is available to everyone; content that masking does change is available to everyone in masked form and to class‑authorized callers in original form; and content whose class is configured `store_original: false` (e.g. SSN/`RESTRICTED`) is **never stored, never indexed, never embedded, and never leaked** in its original form, for anyone.

The design is **additive**, **non‑breaking**, and **rollback‑safe**. All new features are opt‑in, with safe defaults that preserve v1.22 behaviour.

**Next steps:**

- Continue SEC‑2 through SEC‑6 per [implementation plan](../implementation/classification-aware-search/INDEX.md).
- Fold approved sections into the merged design baseline when v1.23 reaches GA.