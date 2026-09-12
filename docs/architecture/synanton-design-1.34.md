# Synanton Design 1.34 — Temporal Versioned Knowledge and Retrieval

> **Document type:** Architecture design document
> **Version:** 1.34
> **Document ID:** `synanton-design-1.34`
> **Revision folded:** 1.2 — incorporates final architecture-review clarifications (see §44 Revision history)
> **Date:** 2026-09-12
> **Status:** Approved (architecture) — implementation not started
> **Normative security baseline:** Design 1.23 (see §36)
> **Primary domains:** Legal, regulatory, contracts, policies, standards, controlled documents
> **Related Designs:** [synanton-design-1.23.md](./synanton-design-1.23.md) — security baseline; temporal filtering is independent of and does not weaken authorization (§36); [synanton-design-1.25.md](./synanton-design-1.25.md) — Knowledge consumes source-version identity and owns derived knowledge, not source history (§2.2, §25); [synanton-design-1.26.md](./synanton-design-1.26.md) — content retention is distinct from source-version metadata retention; legal hold propagates across layers (§16, §17); [synanton-design-1.27.md](./synanton-design-1.27.md) — version-observation and correction events use the common eventing substrate rather than a plane-specific one; [synanton-design-1.28.md](./synanton-design-1.28.md) — owns `VersionSeries`/`SourceVersion` and authoritative source-version history (§2); [synanton-design-1.29.md](./synanton-design-1.29.md) — no identity semantics change; [synanton-design-1.30.md](./synanton-design-1.30.md) — AI provenance remains separate from source temporal provenance; [synanton-design-1.31.md](./synanton-design-1.31.md) — temporal eligibility joins security eligibility as a pre-ranking constraint (§19, §37); [synanton-design-1.32.md](./synanton-design-1.32.md) — owns the public API surface; this design constrains its semantics without freezing field names (§30, §31); [synanton-design-1.33.md](./synanton-design-1.33.md) — no Kubernetes-specific behavior introduced
> **Related docs:** [ADR-012](./decisions/adr-012-temporal-versioned-knowledge-retrieval.md) — companion decision record for this design; [synanton-platform-architecture-1.0.md](./synanton-platform-architecture-1.0.md) — capstone integration

> **Implementation principle:** Design 1.34 extends the existing Synanton architecture. It does not move version-series ownership out of Ingestion (1.28) into Knowledge, does not weaken the security baseline established by Design 1.23, and does not introduce a competing eventing or workflow mechanism alongside Design 1.27. It defines the cross-plane temporal semantics and invariants; each plane listed above remains the owner of its own state (see §2).

---

# 1. Executive summary

Synanton already models source state through immutable `SourceVersion` objects and treats knowledge, search indexes and analytics as derived state.

Design 1.34 adds a **temporal version series** capability.

The purpose is not to create a separate document-management subsystem. The purpose is to make historical source state and temporal retrieval first-class platform semantics.

This is particularly important for legal and regulatory information:

> What did this regulation say on 15 May 2025?

> Which contract version was applicable when the transaction occurred?

> What changed between the January and July versions?

The design establishes:

```text
Source
   │
   ▼
Version Series
   │
   ├── SourceVersion 1
   ├── SourceVersion 2
   ├── SourceVersion 3
   └── ...
```

Each version has both:

```text
observation time
```

and, when known:

```text
validity interval
```

The design deliberately separates:

```text
"When did Synanton observe this version?"
```

from:

```text
"When was this version applicable?"
```

The design also resolves the architecture concerns that must not remain implementation-time decisions:

1. ownership of the version-series model;
2. multiple temporally eligible sources;
3. time-zone semantics;
4. deletion, correction and legal hold;
5. temporal filtering performance;
6. minimum API semantic constraints;
7. graph projections, conflicting validity, corrections, retention, `current`, and cross-source queries.

---

# 2. Architectural ownership

## 2.1 Ownership is split by responsibility

The version series is **owned by Design 1.28 Ingestion**.

This follows the Platform Architecture 1.0 authority model:

```text
Source state        → Ingestion 1.28
Canonical knowledge → Knowledge 1.25
Search projections  → Search 1.31
External contract   → Platform API 1.32
```

The platform architecture explicitly identifies 1.28 as the acquisition plane responsible for source identity, versioning, synchronization and deletion.

Design 1.34 therefore does **not** move version ownership into Knowledge.

Instead:

```text
                         Source history
                              │
                              ▼
                     ┌─────────────────┐
                     │ Ingestion 1.28  │
                     │                 │
                     │ VersionSeries   │
                     │ SourceVersion   │
                     │ validity        │
                     └────────┬────────┘
                              │
                       provenance
                              │
             ┌────────────────┼────────────────┐
             ▼                ▼                ▼
       Content Cache      Knowledge          Search
          1.26              1.25              1.31
```

The platform architecture currently states that Ingestion owns source identity/versioning/synchronization/deletion and Knowledge owns canonical knowledge. This proposal follows that boundary rather than creating a competing ownership model.

## 2.2 Responsibilities by plane

### Ingestion 1.28 owns

- `VersionSeries`;
- `SourceVersion`;
- source revision detection;
- version ordering;
- `observed_at`;
- optional `published_at` where the source declares it;
- source-declared validity metadata;
- correction events concerning source state;
- source lifecycle state;
- source deletion and retention state;
- authoritative source-version history.

### Content Cache 1.26 owns

- immutable content artifacts;
- content retention;
- content-addressed storage;
- legal-hold behavior for content artifacts.

### Knowledge 1.25 owns

- knowledge derived from a specific source version;
- dependency relationships;
- recalculation;
- derived knowledge lifecycle;
- provenance propagation.

### Search 1.31 owns

- temporal candidate filtering;
- temporal search projections;
- temporal retrieval behavior;
- ranking after eligibility filtering.

### Platform API 1.32 owns

- public representation of temporal queries;
- compatibility;
- resource and operation semantics.

### Design 1.34 owns

The cross-plane **temporal semantics and invariants**.

It does not own another copy of the source-version state.

---

# 3. Version series

A `VersionSeries` identifies the historical sequence belonging to one logical source.

```text
VersionSeries
 ├── series_id
 ├── source_id
 ├── current_source_state
 └── versions
       ├── v1
       ├── v2
       ├── v3
       └── ...
```

Each `SourceVersion` is immutable.

Conceptually:

```text
SourceVersion
 ├── version_id
 ├── series_id
 ├── source_id
 ├── source_revision
 ├── content_digest
 ├── published_at       (optional, source-declared)
 ├── observed_at
 ├── validity
 ├── supersedes_version_id
 ├── lifecycle_state
 └── provenance
```

The exact physical schema remains a 1.28 implementation concern.

## 3.1 Series continuity and re-identification

A source may be renumbered or reissued under a different identifier. For example:

```text
Regulation R-42 (2020)
    ↓ reissued as
Regulation R-42/2025 (consolidated)
```

Whether the new identifier:

- continues the same `VersionSeries`;
- begins a new `VersionSeries`;
- creates a linked series;

is a **source-declared or domain-policy decision**.

The platform SHALL NOT automatically merge version series based on:

- content similarity;
- title similarity;
- metadata heuristics;
- search index overlap.

If series linkage is required, it must be established by explicit source metadata or domain policy and recorded as provenance.

---

# 4. Temporal model

Design 1.34 defines three temporal dimensions.

## 4.1 Publication time

Publication time describes when the source declares the version to have been issued.

```text
published_at
```

It answers:

> When was this version published by its originator?

`published_at` is optional. Not every source provides it. When present, it is retained as source metadata and provenance.

## 4.2 Observation time

Observation time describes when Synanton acquired or observed the source state.

```text
observed_at
```

It answers:

> When did Synanton know about this version?

## 4.3 Validity time

Validity time describes when the source version is asserted to be applicable.

```text
valid_from
valid_to
```

It answers:

> When does this version apply?

These three dimensions are independent.

Example:

```text
Amendment v8

published_at = 2025-05-01
observed_at  = 2025-05-03
valid_from   = 2025-07-01
```

On 15 May 2025 Synanton knows about v8, but v8 is not yet effective.

The distinction between `published_at`, `observed_at`, and `valid_from` matters for legal auditability:

- `published_at` is what the source declared.
- `observed_at` is what Synanton recorded.
- `valid_from` is when the version applies.

The platform SHALL retain all three when available, and SHALL NOT collapse them into a single timestamp.

---

# 5. Time-zone semantics

Time-zone handling is normative.

## 5.1 Storage

Temporal instants SHALL be represented internally as UTC instants.

The source's original temporal representation SHOULD also be retained where useful:

```text
valid_from_instant = 2025-07-01T00:00:00Z

source_valid_from = "2025-07-01"
source_time_zone  = "Europe/Amsterdam"
```

The original representation is provenance, not the canonical comparison value.

## 5.2 Query timestamps

Point-in-time queries SHALL resolve to an instant.

Conceptually:

```text
as_of = 2025-06-30T22:00:00Z
```

rather than an ambiguous bare local timestamp.

An API may accept an offset-bearing timestamp:

```text
2025-06-30T23:30:00-04:00
```

but it must normalize it to a UTC instant before temporal comparison.

## 5.3 Date-only legal statements

Legal sources frequently state:

```text
"effective 1 July 2025"
```

without a time zone.

Synanton SHALL NOT invent a universal UTC interpretation.

The ingestion adapter or domain policy must provide the interpretation, for example:

```text
jurisdiction = Europe/Amsterdam
local date = 2025-07-01
```

which resolves to the appropriate UTC instant.

If the interpretation cannot be established:

```text
validity = UNKNOWN
```

rather than a guessed timestamp.

## 5.4 Jurisdiction-specific semantics

Jurisdiction-specific interpretation belongs to domain/source policy.

The platform provides:

```text
instant normalization
+
source temporal provenance
```

It does not claim that every legal date has one globally correct time zone.

---

# 6. Validity interval semantics

The canonical interval is:

```text
[valid_from, valid_to)
```

Therefore:

```text
valid_from <= t
AND
(valid_to IS NULL OR t < valid_to)
```

A version with:

```text
valid_to = null
```

has no known end of validity.

A missing `valid_from` or `valid_to` is not automatically replaced by ingestion time or publication time.

---

# 7. Multiple independent sources

A critical distinction is:

> Temporal validity is evaluated per source version, not globally across the platform.

There may be many independently valid sources at the same instant.

Example:

```text
EU Regulation R-42
  valid: 2024-01-01 → open

National Directive D-17
  valid: 2024-06-01 → open

Temporary Emergency Rule E-3
  valid: 2025-04-01 → 2025-07-01
```

At:

```text
2025-05-15
```

all three may be temporally eligible.

This is not an error.

The question of which source governs a particular legal situation may additionally depend on:

- jurisdiction;
- entity type;
- geography;
- transaction type;
- contractual scope;
- priority;
- exceptions;
- transitional provisions.

Those are **applicability rules**, not basic temporal versioning.

---

# 8. Current means a set, not a single global version

`current` SHALL mean:

> Versions currently temporally eligible according to their source/version semantics.

It does not mean:

> One globally latest document.

For one version series:

```text
current version = one or more versions depending on source rules
```

Across the platform:

```text
current = set of currently eligible source versions
```

The logical predicate is:

```text
valid_from <= now
AND
(valid_to IS NULL OR now < valid_to)
AND
validity != UNKNOWN
```

where `now` is evaluated as a UTC instant.

If applicability rules later narrow this set, that is a separate resolver.

---

# 9. Overlapping versions within one series

A normal version series SHOULD have non-overlapping validity:

```text
v1 [2024-01-01, 2024-07-01)
v2 [2024-07-01, 2025-01-01)
v3 [2025-01-01, ∞)
```

If overlapping validity occurs within a single version series, Synanton SHALL:

1. retain the source state;
2. flag the overlap explicitly;
3. return **all** temporally eligible versions to the caller;
4. attach an overlap indicator to the result set.

Example:

```text
v5 [2024-01-01, 2025-06-01)
v6 [2025-01-01, 2025-12-01)

Query as_of = 2025-03-15

Result:
  - v5   (overlap=true)
  - v6   (overlap=true)
```

The platform SHALL NOT silently select one version based only on:

- ingestion time;
- version number;
- lexical ordering;
- search score;
- publication date.

A source-specific or domain-specific resolver may later establish which version applies. That resolver is a **domain applicability layer**, not basic temporal versioning.

---

# 10. Temporal applicability versus legal applicability

Temporal eligibility answers:

> Was this version valid at time T?

Legal applicability may additionally ask:

> Did this instrument govern this party, jurisdiction, transaction or provision?

Therefore:

```text
Temporal eligibility
        │
        ▼
candidate sources
        │
        ▼
domain applicability
        │
        ▼
retrieval / reasoning
```

The first implementation only guarantees the first step.

This prevents the platform from pretending that timestamp filtering is a complete legal reasoning engine.

---

# 11. Cross-source temporal queries

A cross-source query resolves temporal eligibility independently for each source.

Example:

> Was Contract C-1007's termination clause consistent with Regulation R-42 on 15 May 2025?

Resolution:

```text
Contract C-1007
    └── resolve version at 2025-05-15

Regulation R-42
    └── resolve version(s) at 2025-05-15
```

Then:

```text
temporal join
      │
      ▼
domain comparison
```

The initial implementation SHALL resolve temporal state independently per source.

If one source has no valid historical state:

```text
source history unavailable
```

must remain explicit.

The query must not silently substitute another date or current version.

---

# 12. Legal example: regulation and national directive

```text
EU Regulation R-42
  v11: 2025-02-01 → 2026-02-01

National Directive D-17
  v4: 2025-01-01 → open
```

Question:

> What reporting obligations applied in the Netherlands on 15 May 2025?

Temporal resolution produces:

```text
R-42 v11
D-17 v4
```

The next step is a domain applicability resolver that understands jurisdiction and precedence.

This is preferable to forcing Design 1.34 to choose one document globally.

---

# 13. Conflicting validity assertions

Conflicting assertions SHALL be retained.

Example:

```text
Source metadata:
  effective = 2025-04-01

Document text:
  effective = 2025-05-01
```

Synanton records:

```text
canonical validity
+
conflicting assertion
+
provenance
```

The platform must expose the chosen canonical interpretation and the alternatives when provenance is requested.

Conflict resolution SHALL be:

- explicit;
- source/domain-specific;
- auditable;
- never silently embedded in generic search ranking.

---

# 14. Correction model

A correction is different from a normal version.

Suppose:

```text
v11
  valid_from = 2025-02-01
```

Later the publisher says the metadata was incorrect:

```text
correction c1
  corrects = v11
  valid_from = 2025-02-15
```

The original `SourceVersion` remains immutable.

The correction becomes an explicit source-state fact.

Conceptually:

```text
SourceVersion v11
        │
        └── Correction c1
                │
                ▼
       corrected interpretation
```

## 14.1 Effective historical interpretation

A normal historical query uses the **authoritative corrected interpretation**.

A specialized audit query may request:

```text
original_observation
```

to reproduce what Synanton believed before the correction.

This creates a useful distinction:

```text
historical source state
vs.
historical Synanton observation
```

Both remain auditable.

## 14.2 Impact classification

A correction SHALL be classified before triggering downstream processing:

```text
metadata-only correction
    affects: validity interval, published_at, source_revision
    does not affect: content, semantic meaning

content correction
    affects: content_digest, extracted content, derived knowledge
```

### Metadata-only corrections

A metadata-only correction requires:

```text
search projection metadata update
```

It does **not** require full derived-knowledge recalculation. The affected search projections are updated in place (or via a new projection generation if the backend requires it - see §20.4).

### Content corrections

A content correction triggers the full impact path:

```text
Correction
    │
    ▼
Resolutor
    │
    ▼
affected knowledge
    │
    ▼
Equalix / recalculation
    │
    ▼
search projection update
```

Existing knowledge is not silently mutated without provenance.

This classification prevents unnecessary recalculation for pure temporal metadata changes.

---

# 15. Temporal lifecycle

A source version has lifecycle state independent of its validity interval.

Recommended states:

```text
ACTIVE
SUPERSEDED
RETENTION_EXPIRED
LEGAL_HOLD
DELETED
CORRECTED
```

These states have distinct meanings.

### `ACTIVE`

Available according to normal retention and authorization policy.

### `SUPERSEDED`

A newer source state exists. The historical version remains available unless retention policy says otherwise.

### `RETENTION_EXPIRED`

Normal retrieval is no longer allowed because retention has expired.

### `LEGAL_HOLD`

Retention expiry is suspended by an applicable legal hold.

### `DELETED`

The source state/content has been deleted according to policy.

### `CORRECTED`

A correction changes the authoritative interpretation of the historical source state.

`CORRECTED` does not mean the original immutable observation is erased.

## 15.1 State transition rules

The following transitions are normative.

| From                | To                  | Allowed     | Notes                                                |
| ------------------- | ------------------- | ----------- | ---------------------------------------------------- |
| `ACTIVE`            | `SUPERSEDED`        | Yes         | A newer version has been observed in the same series |
| `ACTIVE`            | `CORRECTED`         | Yes         | A correction affects this version                    |
| `ACTIVE`            | `RETENTION_EXPIRED` | Yes         | Retention policy expiry                              |
| `SUPERSEDED`        | `RETENTION_EXPIRED` | Yes         | Retention policy expiry                              |
| `SUPERSEDED`        | `CORRECTED`         | Yes         | A correction affects this version                    |
| `RETENTION_EXPIRED` | `LEGAL_HOLD`        | Yes         | A legal hold is applied after expiry                 |
| `ACTIVE`            | `LEGAL_HOLD`        | Yes         | A legal hold is applied                              |
| `SUPERSEDED`        | `LEGAL_HOLD`        | Yes         | A legal hold is applied                              |
| `LEGAL_HOLD`        | `ACTIVE`            | Yes         | Legal hold released; version still current           |
| `LEGAL_HOLD`        | `SUPERSEDED`        | Yes         | Legal hold released; version superseded              |
| `LEGAL_HOLD`        | `RETENTION_EXPIRED` | Yes         | Legal hold released; retention already expired       |
| Any state           | `DELETED`           | Conditional | Only when retention, legal hold, and policy permit   |
| `DELETED`           | any state           | **No**      | Deletion is terminal                                 |
| `CORRECTED`         | `SUPERSEDED`        | Yes         | A newer version arrives after correction             |
| `CORRECTED`         | `RETENTION_EXPIRED` | Yes         | Retention expiry                                     |
| `CORRECTED`         | `CORRECTED`         | Yes         | Multiple corrections to the same version             |

Additional rules:

- `DELETED` is terminal. A deleted version SHALL NOT be reinstated.
- `CORRECTED` is not terminal; subsequent corrections are allowed and each is recorded as a distinct correction event.
- `LEGAL_HOLD` may coexist with `CORRECTED`; the hold protects the version regardless of correction.
- `SUPERSEDED` does not imply `RETENTION_EXPIRED`. A superseded version may remain `ACTIVE` from a retrieval standpoint until retention policy applies.

---

# 16. Retention across planes

Retention is not one global switch.

The layers are:

```text
SourceVersion metadata
        │
        ▼
Content artifact
        │
        ▼
Derived knowledge
        │
        ▼
Search projection
```

Each layer may have its own retention mechanics.

## 16.1 SourceVersion metadata

Version metadata SHOULD generally be retained longer than transient search projections because it is needed for historical lineage.

## 16.2 Content artifacts

Governed by Content Cache 1.26 retention policy.

## 16.3 Derived knowledge

Governed by Knowledge 1.25 lifecycle and dependency policy.

## 16.4 Search projections

May be rebuilt from authoritative retained state.

Therefore search-index retention is not equivalent to source-version retention.

---

# 17. Legal hold

A legal hold SHALL override ordinary retention expiry for the resources covered by the hold.

Example:

```text
v7
  retention expiry: 2027-01-01

legal hold:
  begins: 2026-12-01
  scope: Contract C-1007
```

The relevant source/version/content cannot be purged merely because its ordinary retention date arrives.

Legal hold propagation must cover all required evidence layers:

```text
SourceVersion
ContentArtifact
required derived knowledge
required provenance
```

Search projections themselves may be rebuilt, but the authoritative evidence required for reconstruction must remain retained.

---

# 18. Historical query against deleted content

A historical query must never silently fall back to another version.

If the requested historical evidence was deleted under an authorized policy, the system returns an explicit state such as:

```text
VERSION_DELETED_BY_POLICY
```

or:

```text
HISTORICAL_CONTENT_UNAVAILABLE
```

The exact API error code is deferred to 1.32, but the semantic behavior is normative.

Example:

```text
Query:
  as_of = 2020-05-01

History:
  v3 was applicable
  v3 content was legally deleted
```

Result:

```text
historical state identified
content unavailable
```

not:

```text
return v4
```

---

# 19. Search architecture

Temporal filtering is an eligibility constraint.

The retrieval pipeline is:

```text
Query
  │
  ├── security eligibility
  │
  ├── temporal eligibility
  │
  └── other structural constraints
          │
          ▼
    candidate generation
      ├── lexical
      ├── vector
      └── graph
          │
          ▼
        ranking
          │
          ▼
       reranking
```

Temporal filtering SHALL occur before ranking.

A newer version must not win merely because it has higher semantic similarity when the query is historical.

---

# 20. Temporal index strategy

Temporal filtering must scale independently of semantic retrieval.

## 20.1 Required searchable fields

Search projections SHOULD expose:

```text
source_id
version_id
valid_from
valid_to
```

and appropriate lifecycle/security metadata.

## 20.2 Avoid per-version indexes

The logical model SHALL NOT require:

```text
contract-v1-index
contract-v2-index
contract-v3-index
```

Instead, versions coexist in a logical projection and are filtered.

Physical partitioning remains an implementation optimization.

## 20.3 Two-stage temporal resolution

For high-cardinality source histories, use:

```text
Stage 1:
(source_id, as_of) → eligible version(s)

Stage 2:
(version_id) → semantic retrieval
```

This avoids repeatedly scanning thousands of versions for every query.

## 20.4 Projection lifecycle interaction

Temporal metadata updates on existing versions (corrections, lifecycle state changes, validity refinements) follow the projection lifecycle rules of Design 1.31.

The default rule is:

```text
small metadata-only update
    → in-place projection update

structural change affecting many versions
    → new projection generation
    → atomic activation
```

The decision threshold is an implementation concern, but the semantics are:

> A temporal metadata update SHALL NOT require a full index rebuild unless the underlying search backend requires it for correctness.

This preserves the atomic-activation guarantee of Design 1.31 while avoiding unnecessary rebuilds for lightweight corrections.

---

# 21. Temporal resolution cache

A derived temporal cache may maintain:

```text
(source_id, time_bucket)
        →
version set / temporal resolution
```

The cache is not authoritative.

## 21.1 Invalidation

A new version invalidates only affected source/time ranges.

A correction invalidates the interval affected by the correction and any downstream cached resolutions.

A deletion invalidates cached results that referenced the deleted state.

A legal hold does not normally require invalidating temporal resolution because it changes retention state, not validity.

Conceptually:

```text
new version
    │
    ▼
invalidate affected source intervals
```

```text
correction
    │
    ▼
invalidate corrected interval
```

```text
deletion
    │
    ▼
invalidate cached historical results
```

## 21.2 Rebuild

The cache can always be rebuilt from authoritative:

```text
SourceVersion
+
validity
+
correction state
```

Therefore cache loss does not cause loss of temporal truth.

---

# 22. Performance model

The design does not mandate a single performance target before measurement, but implementation SHALL establish benchmarks.

The important asymptotic property is:

```text
as_of query cost
≈ temporal resolution cost
 + normal search cost
```

not:

```text
scan all historical chunks
 + semantic search
```

## 22.1 Desired characteristics

For a source with:

```text
N versions
M chunks per version
```

an `as_of` query should resolve the applicable version in approximately:

```text
O(log N)
```

or equivalent indexed lookup behavior, rather than:

```text
O(N)
```

for normal source histories.

## 22.2 Large multi-tenant search

For a query across many sources, temporal eligibility should be evaluated using indexed fields and candidate structures rather than materializing every historical version.

## 22.3 Benchmark requirement

The implementation proposal SHOULD establish measurable targets such as:

```text
p95 temporal resolution latency
p95 as_of search latency
cache hit ratio
candidate reduction ratio
index storage overhead
```

The exact numbers should be chosen from benchmark results rather than invented as architecture constants.

---

# 23. Temporal metadata and index bloat

Storing:

```text
valid_from
valid_to
version_id
```

on every chunk can increase index size.

The implementation should therefore evaluate:

### Option A - denormalized fields

Each chunk contains temporal fields.

Advantages:

- simple filtering;
- efficient backend execution.

Cost:

- repeated metadata.

### Option B - version-level temporal prefilter

Resolve version IDs first, then constrain semantic search to those IDs.

Advantages:

- less duplicated temporal metadata.

Cost:

- more complex query planning.

### Recommendation

Support the logical two-stage model first:

```text
source/time
    ↓
version set
    ↓
knowledge retrieval
```

and denormalize fields where the backend benefits from it.

---

# 24. Graph semantics

The initial implementation treats graph temporal eligibility at the **knowledge/source-version level**.

Example:

```text
Entity A
   │
   └── regulated_by → Regulation R-42 v11
```

A historical query first limits knowledge to the source versions eligible at the requested time.

The graph edge itself does not initially need an independent validity interval.

## 24.1 Future extension

A later design may introduce temporal graph edges:

```text
Entity A
  └── regulated_by
        ├── valid_from
        └── valid_to
```

This becomes useful when the relationship itself changes independently of the source document.

Design 1.34 therefore reserves the semantic space but does not require temporal edge indexing in the first implementation.

---

# 25. Temporal knowledge

Knowledge is derived from a source version.

Conceptually:

```text
Knowledge K1
    │
    └── derived_from → SourceVersion v11
```

The validity of K1 can be resolved through its provenance.

A future extension may materialize:

```text
knowledge.valid_from
knowledge.valid_to
```

for performance or semantic convenience.

This is a projection, not a second authority.

---

# 26. Temporal RAG

Temporal constraints must be applied before LLM context construction.

Incorrect:

```text
retrieve current documents
        ↓
tell LLM "answer as of 2024"
```

Correct:

```text
question
  ↓
temporal resolution
  ↓
eligible versions
  ↓
hybrid retrieval
  ↓
reranking
  ↓
LLM context
  ↓
answer + provenance
```

This prevents later amendments from contaminating historical answers.

---

# 27. Legal example: contract versions

```text
Contract C-1007

v1
  2023-01-01 → 2024-01-01
  termination = 30 days

v2
  2024-01-01 → 2025-01-01
  termination = 60 days

v3
  2025-01-01 → open
  termination = 90 days
```

Queries:

```text
"What is the termination notice?"
→ 90 days

"What was the termination notice on 2024-03-15?"
→ 60 days

"What changed from 2023 to 2025?"
→ 30 → 60 → 90 days
```

All three use the same immutable version series.

---

# 28. Legal example: publication before effectiveness

```text
Amendment v8

published: 2025-05-01
observed:  2025-05-03
effective: 2025-07-01
```

On:

```text
2025-05-15
```

v8 is:

```text
known
```

but not:

```text
temporally effective
```

On:

```text
2025-07-15
```

v8 is eligible.

This is why `observed_at` cannot be substituted for `valid_from`, and why `published_at` is retained separately.

---

# 29. Legal example: independent overlapping sources

```text
EU Regulation R-42
  valid: 2024-01-01 → open

Dutch Directive D-17
  valid: 2024-06-01 → open

Emergency Rule E-3
  valid: 2025-04-01 → 2025-07-01
```

At:

```text
2025-05-15
```

all three are temporally eligible.

The platform should return:

```text
R-42
D-17
E-3
```

to the applicability layer.

It should not decide that only one is "the current law" based on timestamp alone.

---

# 30. API semantic contract

The HTTP/RPC surface remains owned by Design 1.32, but 1.34 defines mandatory semantics.

## 30.1 `current`

Meaning:

> Return currently temporally eligible versions.

It returns a set, not one global version.

## 30.2 `as_of`

Meaning:

> Resolve temporal eligibility at one exact instant.

Input must represent an unambiguous instant.

## 30.3 `range`

For the first implementation, `range` SHALL mean:

> Return versions whose validity interval overlaps the requested interval.

It is **not** equivalent to "state at start and end."

The overlap condition is:

```text
version.valid_from < range.to
AND
(version.valid_to IS NULL OR version.valid_to > range.from)
```

A future API may add a separate mode for endpoint snapshots.

## 30.4 `version`

Meaning:

> Restrict retrieval to an explicitly identified source version.

No temporal inference is required.

## 30.5 `compare`

Meaning:

> Compare two explicitly resolved temporal states or versions.

The result SHALL be structured and provenance-bearing.

It must not be only free-form generated text.

---

# 31. API result provenance

A temporal result should expose at least:

```text
source_id
version_id
source_revision
published_at   (when available)
valid_from
valid_to
observed_at
content_digest
```

when authorized.

Example:

```json
{
  "source_id": "contract-C-1007",
  "version_id": "v2",
  "published_at": "2024-01-02T09:00:00Z",
  "valid_from": "2024-01-01T00:00:00Z",
  "valid_to": "2025-01-01T00:00:00Z",
  "observed_at": "2024-01-03T10:15:00Z"
}
```

The exact field names are subject to 1.32, but their semantics are normative.

---

# 32. Historical answer failure semantics

A temporal query must distinguish:

```text
VERSION_FOUND
VERSION_NOT_FOUND
VALIDITY_UNKNOWN
VALIDITY_CONFLICT
VERSION_OVERLAP
HISTORY_INCOMPLETE
HISTORICAL_CONTENT_UNAVAILABLE
VERSION_DELETED_BY_POLICY
```

A missing historical state must never become a current-state answer.

Example:

```text
Requested:
  2020-01-01

Available history:
  2023-01-01 onward
```

Correct:

```text
HISTORY_INCOMPLETE
```

Incorrect:

```text
return 2023 version
```

---

# 33. Reproducibility

A historical query should remain stable when later versions arrive.

Example:

```text
Query:
  contract C-1007
  as_of = 2024-03-15

Today:
  current = v7
  historical = v4

Tomorrow:
  current = v8
  historical = v4
```

The historical result remains v4 unless an explicit source correction changes the authoritative historical interpretation.

---

# 34. Correction and reproducibility

There are two legitimate questions:

### Authoritative historical state

> What should we now understand the source to have meant?

Use corrected interpretation.

### Historical observation

> What did Synanton know/believe at that time?

Use the original observation state.

These are different query semantics.

The normal legal retrieval mode uses authoritative corrected history.

An audit mode may reproduce historical observation.

---

# 35. Deletion and search projections

Search indexes are derived.

If content is deleted:

```text
authoritative source/content state
        │
        ▼
search projection update
```

A deleted version must be removed from normal retrieval.

If historical metadata remains but content is gone:

```text
metadata:
  version existed

content:
  unavailable
```

The search result must indicate the evidence is unavailable rather than fabricate a replacement.

---

# 36. Security interaction

Temporal filtering does not weaken security.

Eligibility is:

```text
AUTHORIZED
AND
TEMPORALLY_ELIGIBLE
AND
OTHER_CONSTRAINTS
```

Security and temporal state are independent.

A user must not gain access to an old contract merely because it is historical.

Security reclassification can also make an otherwise temporally eligible version unavailable.

The interaction with Design 1.23 security lifecycle must therefore be evaluated before retrieval.

---

# 37. Search contamination rule

A historical query must not retrieve temporally ineligible evidence merely because it is semantically similar.

For example:

```text
Question:
What was the termination period on 2024-03-15?

Correct:
v2 → 60 days

Incorrect:
v3 → 90 days
```

This becomes a measurable retrieval quality dimension:

```text
Temporal Contamination Rate
```

---

# 38. Evaluation

Temporal retrieval requires dedicated evaluation.

Recommended metrics:

### Version Accuracy

Did the system identify the correct version?

### Temporal Recall@K

Was the historically valid evidence retrieved?

### Temporal NDCG@K

How highly was the correct historical evidence ranked?

### Historical Answer Accuracy

Did the answer reflect the historical evidence?

### Temporal Contamination Rate

How often did an ineligible version appear in retrieved evidence?

### Historical Availability Rate

How often could the requested historical state actually be reconstructed?

---

# 39. Initial implementation phases

## Phase 1 - 1.28 extension

Implement:

```text
VersionSeries
SourceVersion
published_at
observed_at
valid_from
valid_to
supersedes_version_id
lifecycle_state
```

## Phase 2 - provenance

Ensure Knowledge references the source version.

## Phase 3 - temporal normalization

Implement:

```text
UTC instant
+
source temporal representation
+
time-zone provenance
```

## Phase 4 - search

Add temporal candidate eligibility.

## Phase 5 - `current` and `as_of`

Implement the minimum temporal API semantics.

## Phase 6 - retention/correction

Implement:

```text
correction classification (metadata vs content)
legal hold
retention expiry
historical unavailable
```

## Phase 7 - benchmarking

Measure:

```text
temporal resolution latency
as_of p95
cache hit ratio
temporal contamination
version accuracy
```

## Phase 8 - range/compare

Only after point-in-time retrieval is stable.

---

# 40. Non-goals

Design 1.34 does not create:

- a full enterprise content-management system;
- document authoring;
- check-in/check-out;
- approval workflows;
- a legal advice engine;
- universal legal applicability reasoning;
- a mandatory temporal graph database;
- one index per document version;
- a specific database/search implementation;
- automatic interpretation of ambiguous legal dates without source/domain policy.

---

# 41. Normative invariants

The following are proposed as additions to the platform's temporal architecture invariants.

1. **Version-series authority belongs to Ingestion 1.28.**
2. **Knowledge 1.25 consumes source-version identity and owns derived knowledge, not source history.**
3. **Search 1.31 owns temporal retrieval behavior but not authoritative version history.**
4. **A source version is immutable.**
5. **Source revision and content digest are distinct.**
6. **Publication time, observation time and validity time are distinct.**
7. **Canonical temporal instants are normalized to UTC.**
8. **Original source date/time representation and time-zone provenance are retained where relevant.**
9. **Temporal eligibility is evaluated per source version.**
10. **Multiple independent sources may be simultaneously temporally eligible.**
11. **The platform does not assume one globally applicable source at a timestamp.**
12. **Legal/domain applicability is separate from temporal eligibility.**
13. **Unknown or conflicting validity is explicit.**
14. **Overlapping validity within a single series is flagged, not silently resolved.**
15. **Corrections do not mutate immutable source versions.**
16. **Corrections are classified as metadata-only or content-affecting before downstream processing.**
17. **Metadata-only corrections update projections; content corrections trigger recalculation.**
18. **Superseded does not mean deleted.**
19. **Legal hold overrides ordinary retention.**
20. **Deleted is terminal; a deleted version is not reinstated.**
21. **Historical queries never silently fall back to current content.**
22. **Temporal filtering occurs before ranking.**
23. **Security eligibility remains independent and mandatory.**
24. **Temporal caches and search indexes are derived state.**
25. **Temporal caches are rebuildable from authoritative source history.**
26. **Historical search results expose version and temporal provenance subject to authorization.**
27. **Current retrieval may return multiple independently eligible source versions.**
28. **Version series continuity across source re-identification is a domain-policy decision, not a platform heuristic.**
29. **API representations may evolve, but the temporal semantic contract in this design must be preserved.**

---

# 42. Open questions that remain intentionally open

After resolving the major architecture gaps, several implementation choices can remain open without weakening the design:

1. Exact physical schema for `VersionSeries`.
2. Exact search backend strategy for interval filtering.
3. Temporal cache bucket granularity.
4. Exact p95 performance targets after benchmark results.
5. Exact API resource paths and serialization.
6. Provision-level legal applicability modeling.
7. Temporal graph-edge semantics.
8. Semantic diff algorithm.
9. Domain-specific conflict-resolution policies.
10. Threshold for in-place projection update vs full generation rebuild for metadata-only corrections.

These are implementation/design follow-ups rather than unresolved ownership or semantic questions.

---

# 43. Architecture decision

Design 1.34 establishes **Temporal Versioned Knowledge and Retrieval** as a cross-plane platform capability.

The authoritative model remains:

```text
External Source
      │
      ▼
Ingestion 1.28
      │
      ├── SourceIdentity
      └── VersionSeries
             │
             ├── SourceVersion v1
             ├── SourceVersion v2
             └── SourceVersion v3
                    │
                    ├── published_at
                    ├── observed_at
                    ├── validity
                    └── lifecycle
             │
             ▼
Content Cache 1.26
             │
             ▼
Knowledge 1.25
             │
             ▼
Search 1.31
             │
             ├── temporal eligibility
             ├── security eligibility
             └── semantic retrieval
             │
             ▼
Platform API 1.32
```

The most important architectural boundary is:

> **Ingestion owns source history. Knowledge owns what Synanton derives from that history. Search owns how that history is retrieved.**

For legal and regulatory applications, this enables a stronger capability than ordinary document versioning:

> **Synanton can determine not only which version exists, but which versions were temporally eligible, when Synanton observed them, what knowledge was derived from them, and which historical evidence supports an answer.**

That makes point-in-time retrieval a platform primitive rather than an application-specific workaround.

---

# 44. Revision history

| Revision | Date       | Summary                                                      |
| -------- | ---------- | ------------------------------------------------------------ |
| 1.0      | 2026-09-12 | Initial proposal                                             |
| 1.1      | 2026-09-12 | Resolved architecture review concerns (ownership, time zones, overlapping sources, corrections, retention, API semantics, graph interaction) |
| 1.2      | 2026-09-12 | Final review clarifications: added `published_at`; explicit overlap return semantics; correction impact classification; lifecycle state transition table; projection generation interaction; series re-identification policy |
| 1.2 (folded) | 2026-09-12 | Accepted by the Architecture Review Board; folded into the accepted design set as `synanton-design-1.34.md` and integrated into the [Synanton Platform Architecture 1.0](./synanton-platform-architecture-1.0.md) capstone document; see [ADR-012](./decisions/adr-012-temporal-versioned-knowledge-retrieval.md) |

---

# 45. References

- [Synanton Platform Architecture 1.0](./synanton-platform-architecture-1.0.md) — capstone integration
- [Design 1.23 - Security](./synanton-design-1.23.md)
- [Design 1.25 - Knowledge](./synanton-design-1.25.md)
- [Design 1.26 - Content Cache](./synanton-design-1.26.md)
- [Design 1.27 - Eventing and Workflow](./synanton-design-1.27.md)
- [Design 1.28 - Ingestion](./synanton-design-1.28.md)
- [Design 1.29 - Identity](./synanton-design-1.29.md)
- [Design 1.30 - AI Runtime](./synanton-design-1.30.md)
- [Design 1.31 - Search](./synanton-design-1.31.md)
- [Design 1.32 - Platform API](./synanton-design-1.32.md)