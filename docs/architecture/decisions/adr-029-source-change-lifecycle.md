# ADR-029: Source-change lifecycle — revisioned replacement, not incremental append

**Status:** Proposed for review — 2026-11-12
**Basis:** Phase 2 inspection found that Synanton can construct a canonical graph but cannot yet maintain it when a source document changes. The source, its revisions, the assertions they support, and the graph/search projections are distinct concepts and must have separate lifecycles.

## Decision

### 1. Immutable source revision model

A logical source is separate from its immutable revisions.

| Field | Meaning | Owner |
|---|---|---|
| `tenant_id` | Tenant ownership | `source_versions` |
| `source_id` | Stable logical document identity | `source_versions` |
| `revision_id` | Immutable revision identifier (`source_version_id`) | `source_versions` |
| `generation` | Monotonic source generation; fencing token | `source_versions` |
| `content_hash` | Detect actual content changes | `source_versions` |
| `previous_revision_id` | Revision lineage | `source_versions` |
| `status` | `CURRENT`, `SUPERSEDED`, `STALE`, `DELETED` | `source_versions` |
| `created_at` | Revision creation time | `source_versions` |

The **generation counter lives in Cassandra `source_versions`** (new columns alongside today's version row). It is monotonic per `(tenant_id, connector_id, source_id, resource_id)`; timestamps are never used to decide which revision wins. A new revision is registered even when extraction fails, so the fence exists before downstream work starts. A content hash equal to the current revision is a no-op, not a new generation.

Mapping: the existing `source_versions` row remains the source/revision registry; `version_id` becomes the immutable `revision_id`, and `generation`/`content_hash`/`previous_revision_id` become required lifecycle fields.

### 2. Staged update pipeline

1. revision detected and registered;
2. extraction produces mentions, candidate assertions, relations and evidence;
3. validation covers extraction completeness, ontology, evidence and resolution;
4. staging writes `:ExtractedMention`/`:EXTRACTED_RELATION`/extraction manifest without retiring old knowledge;
5. conditional projection commit verifies the source generation is still current and activates the new projection;
6. retirement and propagation retire old-only support, then update search and downstream projections.

The previous projection remains available until step 5 succeeds. A failed or partial revision never authorizes retirement. Staging may reuse the existing extracted-mention space; it must not create canonical `:REL` before validation.

### 3. Provenance-aware assertions

Treat `:REL` as the canonical assertion, `:RelationEvidence` as evidence, and the support relationship as many-to-many.

Recommended representation, extending the existing model:

```cypher
(:Entity)-[:REL {canonical_relation_id, status}]->(:Entity)
(:REL)-[:SUPPORTED_BY]->(:RelationEvidence {evidence_id, source_version_id, status})
(:RelationEvidence)-[:EXTRACTED_FROM]->(:SourceRevision)
(:ExtractedMention {source_version_id, assertion_status})
```

Existing `:RelationEvidence.canonical_relation_id` remains for compatibility; `:SUPPORTED_BY` makes the assertion→evidence relationship explicit. `:ExtractedMention` is version-scoped today; retain `source_version_id` plus an assertion status. Phase 2 does not merge mention identities across revisions.

Assertion states: `ACTIVE`, `UNSUPPORTED`, `CONFLICTED`, `SUPERSEDED`, `REVIEW_REQUIRED`.

- A `:REL` is active only while at least one current-supported evidence exists.
- Retire evidence before retiring an assertion; never delete historical assertions/evidence in routine updates.
- Conflicting active sources produce `CONFLICTED`/review, never silent overwrite.

### 4. Source-version fencing

Every extraction and downstream graph mutation carries `(tenant_id, source_id, revision_id, generation, event_id)`.

Before graph mutation, the writer reads the authoritative current generation from `source_versions` and conditionally commits only if the operation's generation equals it. The comparison and mutation must be protected by one atomic conditional operation or an equivalent fenced transaction. If an older revision finishes after a newer one is accepted, the older revision is rejected in full:

```text
if extraction.generation != source.current_generation:
    return STALE_EXTRACTION   # no partial graph/search mutation
```

The same check applies before assertion retirement and search indexing; a worker that lost its fence cannot mutate either projection. Checking at extraction start only is insufficient.

### 5. Complete versus partial extraction

Absence of an assertion is not evidence of removal. Only a **complete, validated** revision may authorize retirement.

An extraction manifest is required:

| Field | Purpose |
|---|---|
| `source_revision_id` | Revision |
| `extraction_run_id` | Run identity |
| `generation` | Fence |
| `expected_chunks` / `processed_chunks` / `failed_chunks` | Completeness |
| `accepted_assertions` / `rejected_assertions` | Outcome accounting |
| `completion_status` | `COMPLETE`, `PARTIAL`, `FAILED`, `STALE` |
| `model_version` / `prompt_version` | Reproducibility |

Policy:

- complete + validated → eligible for projection replacement;
- partial → preserve old projection, mark review/retry;
- failed → preserve old projection;
- ontology-invalid → do not commit invalid assertions;
- low confidence → review policy;
- stale generation → reject all mutations.

### 6. Explicit replacement and retirement semantics

These are distinct operations:

| Event / operation | Effect |
|---|---|
| `SourceRevisionAccepted` | New immutable revision registered |
| `ExtractionCompleted` | Manifest/outcomes available; no retirement yet |
| `KnowledgeProjectionCommitted` | New revision's knowledge accepted |
| `EvidenceSuperseded` | Old evidence no longer current |
| `AssertionUnsupported` | No current evidence remains |
| `SourceDeleted` | Logical source removed |

`SourceRevisionAccepted` **supersedes `SourcePublished` for lifecycle**. During migration, `SourcePublished` may remain as a compatibility projection of the same fact; it must not become a second authority. `SourceSuperseded` cancels an intermediate revision before full processing. `SourceDeleted` remains distinct from both.

### 7. Search index replacement

For each logical source revision:

- index the new revision and make it visible only after the new projection is accepted;
- remove old revision chunks only when no other live revision references the content-addressed chunk;
- preserve revision metadata for provenance;
- make replacement retryable and idempotent;
- reject stale indexing using the same generation fence;
- use revision/generation visibility or an atomic alias/swap (no mixed old/new live document);
- if new indexing fails, do not present old content as current: mark the source `STALE`, retry with backoff, and surface STALE in API/UI.

### 8. Graph and search as a distributed workflow

Neo4j, Cassandra, Lucene and downstream services cannot share one ACID transaction. Do not simulate global exactly-once.

Use:

- transactional outbox for reliable event publication;
- at-least-once delivery;
- idempotent consumers;
- per-source ordering and generation fencing;
- durable processing state;
- retryable operations;
- reconciliation for incomplete graph/search projections.

Event envelope minimum:

```json
{
  "event_id": "uuid",
  "tenant_id": "tenant-123",
  "source_id": "source-456",
  "revision_id": "revision-2",
  "generation": 2,
  "content_hash": "def456",
  "event_type": "SourceRevisionAccepted",
  "correlation_id": "workflow-789"
}
```

Consumers must be able to reject stale/duplicate work from the envelope without inferring authority.

### 9. Recommended architecture and boundaries

Organize this as a **Source Change Management capability**, not as update logic inside extraction or graph adapters:

1. **Source Revision Registry** — immutable revisions, current generation, hashes (Synflux/Cassandra).
2. **Extraction and Validation** — RAKG, mentions, resolution, ontology validation.
3. **Projection Coordinator** — staging, completeness, generation fencing, conditional commit.
4. **Canonical Graph** — assertions, evidence/support, retirement (Relix).
5. **Search Projection** — revision replacement, visibility, indexing (Synquest).
6. **Reconciliation and Observability** — detect/repair incomplete or divergent projections.

Do not create a standalone microservice yet. Establish lifecycle contracts and implement the coordinator inside existing platform boundaries.

### 10. Acceptance tests, metrics and implementation order

Acceptance tests:

| Scenario | Expected result |
|---|---|
| New revision, complete extraction | New projection committed |
| Same content hash | No unnecessary reprocessing |
| Old extraction completes late | Rejected by fencing; no partial mutation |
| Extraction partially fails | Previous projection preserved |
| Assertion supported by two sources, one changes | Remaining support keeps assertion active |
| Assertion loses all current support | Assertion retired/marked unsupported |
| New revision introduces contradiction | Conflict recorded; no silent overwrite |
| Graph commit succeeds, search update fails | Search repair retryable; STALE observable |
| Search update succeeds, graph commit fails | Stale search visibility prevented or reconciled |
| Duplicate event | No duplicate graph mutation |
| Source deletion | Graph support and search projection handled |
| Worker restarts during replacement | Workflow resumes safely |
| Ontology version diverges | Explicit migration required; no implicit upgrade |

Metrics: revision-acceptance→graph-commit time; revision→search-visible time; stale extraction rejections; active unsupported assertions; incomplete graph/search projections; reconciliation repair rate.

Implementation order:

1. **Revision identity and fencing** — immutable revisions, generation tokens, stale-extraction rejection.
2. **Assertion support lifecycle** — evidence support, multi-source retention, retirement semantics.
3. **Staged graph projection** — extraction manifest, completeness, conditional commit, safe replacement.
4. **Search revision replacement** — update handling, stale visibility, retry/reconciliation.
5. **End-to-end failure testing** — concurrent revisions, partial extraction, restarts, cross-system recovery.

Consequences:
- **Correctness:** old revisions cannot overwrite or reactivate newer knowledge; partial extraction never deletes valid knowledge.
- **Provenance:** evidence and assertions are retained and status-marked, not destroyed.
- **Search:** revision replacement is explicit; stale visibility is observable and retryable.
- **Review:** conflicting evidence is surfaced rather than auto-merged.
- **Migration:** projection/ontology divergence remains a separate workflow; this ADR does not auto-migrate it.
- **Rejected:** treating a document update as another ingestion append.
- **Rejected:** using timestamps for ordering or `SourceDeleted` as the update signal.
- **Rejected:** retiring assertions because one extraction omitted them.
- **Rejected:** physically deleting evidence/assertions during routine updates.
- **Rejected:** a partial extraction authorizing retirement.

## Context

Phase 1 made graph identity, ports, and provisioning explicit. The Phase 2 inspection found the missing operational behavior is source-change maintenance: extraction is version-scoped but not commit-fenced; old graph state is not retired; search projection only removes content on `SourceDeleted`. The system can construct a graph but cannot yet answer what knowledge remains valid after a source changes. This ADR defines the lifecycle before implementation because revision identity, assertion support, staged acceptance, and projection replacement are architectural choices.
