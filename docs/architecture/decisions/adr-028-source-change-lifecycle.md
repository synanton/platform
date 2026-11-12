# ADR-028: Source-change lifecycle replaces stale knowledge without destroying provenance

**Status:** Proposed for review — 2026-11-12
**Basis:** Phase 2 inspection found that ingestion can build a graph but a modified source does not reliably retire old assertions, replace old indexed chunks, or discard stale in-flight extraction. Key findings are recorded in `docs/planning/phase2-knowledge-maintenance.md` and #634.

## Decision

1. **Every extraction and downstream graph mutation carries source-version identity.** The operation carries `(tenantId, sourceId, sourceVersionId)` and the originating event id. Before any graph mutation, the writer queries the authoritative current source version for that source identity. If the operation's version is older than the current active version, the **entire** operation is rejected before writing; it is not partially applied. The rejection logs tenant, source, operation version, current version, and event id.
2. **Extraction is fenced, not merely ordered.** A V1 extraction that finishes after V2 has been accepted is discarded even if V2 has not finished processing. A newer accepted version makes older in-flight outputs stale. No retry of V1 may reactivate old knowledge.
3. **Assertions have an explicit lifecycle.** Canonical `:REL` facts are supported by `:RelationEvidence` carrying `source_version_id` and an explicit status. When a source version is superseded:
   - retire a `:REL` only when it has no `:RelationEvidence` from another active version;
   - preserve the fact when another active version still supports it;
   - mark superseded evidence `status='SUPERSEDED'` and keep the node/edge history;
   - filter superseded evidence/facts from active read paths;
   - flag conflicting evidence from multiple active sources for review; never auto-reconcile it.
   `:ExtractedMention` keeps its version-scoped identity and gains an assertion status; mention nodes and their provenance are retained, not physically deleted. Phase 2 does not attempt cross-version mention identity merging.
4. **Search projection replaces a changed version; deletion is not the change signal.** A new version of an existing source is a change. After the new version is successfully indexed, remove the prior version's indexed chunks/attribution where no other live version still references the content-addressed chunk. Do not rely on `SourceDeleted` for updates or replacements.
5. **Event mapping is explicit.** The existing `SourcePublished` event is the on-wire “new version accepted” signal and is the existing equivalent of `SourceVersionChanged`; Phase 2 does not introduce a duplicate event with that name. The previous version is derived from the source-version history, and the projection consumer performs replacement. `SourceSuperseded` is a new event for an intermediate version replaced before it was fully processed; its consumers cancel in-flight extraction/projection for that version. `SourceDeleted` remains resource deletion, not change.
6. **Failure is observable and fail-closed.** If indexing the new version fails, the old indexed version must not be silently presented as current. The source is marked `STALE`, the failure/retry/backoff is recorded, and the source-status API/UI exposes the stale state. Older evidence may remain for history but active retrieval must surface the staleness; retries continue under the normal backoff policy.
7. **Downstream writes re-check version validity.** Resolution and promotion carry the source version and re-check it before graph mutation. A stale decision or promotion is rejected/superseded and never mutates the canonical graph. This extends Decision 1 past extraction.

Consequences:
- **Correctness:** old versions cannot overwrite or reactivate knowledge; partial extraction is not visible.
- **Provenance:** evidence is status-marked, not destroyed; historical and current evidence remain distinguishable.
- **Search:** changed versions replace old indexed state; stale state is explicit when replacement fails.
- **Review:** conflicting evidence is surfaced for human review rather than auto-merged.
- **Migration:** this ADR does not implement a projection/ontology divergence migration workflow; `DIVERGENT` remains a separate terminal/manual state (ADR-027).
- **Rejected:** using `SourceDeleted` as the update signal; it conflates removal with replacement.
- **Rejected:** deleting `:RelationEvidence` or canonical facts to represent retirement; it loses provenance.
- **Rejected:** applying a partially completed extraction; it can create a mixed-version graph.
- **Rejected:** marking the old version current when the new version's indexing fails.

## Context

Phase 1 made graph identity, ports, and provisioning explicit. Phase 2 inspection shows the missing operational behavior is source-change maintenance: the ingestion path is version-scoped but not current-version fenced; old graph state is not retired; and search projection only removes content on `SourceDeleted`. The resulting system can construct a graph but cannot yet answer “what knowledge remains valid after this source changed?” This ADR defines that lifecycle before implementation, because the fencing, retirement, and replacement choices are architectural and must be reviewed independently of code.
