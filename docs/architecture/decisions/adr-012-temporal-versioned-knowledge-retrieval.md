# ADR-012: Temporal Versioned Knowledge and Retrieval

**Status:** Accepted
**Date:** 2026-09-12
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.34.md](../synanton-design-1.34.md)

## Context

Synanton already modeled source state as immutable `SourceVersion` objects (Design 1.28) and treated knowledge, search indexes and analytics as derived state (Designs 1.25, 1.31), but the platform had no first-class concept of a **temporal version series** — the historical sequence belonging to one logical source, with explicit publication/observation/validity semantics and a normative contract for point-in-time retrieval. This gap is most visible in legal, regulatory, contract and policy domains, where questions such as "what did this regulation say on 15 May 2025?" or "which contract version was applicable when the transaction occurred?" require the platform to reconstruct historical state reliably rather than approximate it from whatever content happens to be currently indexed.

The proposal (`docs/architecture/proposals/v1.34/`) went through its own architecture review before reaching this repository: an initial revision (1.0) was reviewed and returned twelve concerns (ownership ambiguity, missing time-zone semantics, undefined behavior for overlapping validity, no correction-impact classification, implicit lifecycle transitions, no interaction with Design 1.31 projection generations, no source re-identification policy, among others); revision 1.1 resolved all twelve with concrete normative solutions; a follow-up review of 1.1 found it "architecturally sound" and identified six further minor clarifications (adding `published_at` as a distinct temporal dimension, explicit overlap-return semantics, a metadata-only-vs-content-affecting correction classification, a lifecycle state-transition table, an interaction rule with Design 1.31's projection-generation model, and a source-re-identification policy note); revision 1.2 incorporated all six. The proposal file as delivered to this repository already contains the Architecture Review Board's review notes recommending **Accept** followed by the full revision 1.2 document — this ADR treats that recommendation as the basis for acceptance rather than re-deriving it.

Folding the proposal into `synanton-design-1.34.md` involved: stripping the review board's preamble (its review notes, which are not part of the design document itself) so the design document starts at its own title; removing ~108 markdown-export artifacts where a fenced code block's language tag (`text`/`json`) had been separated onto its own line above a bare ` ``` ` fence; normalizing the section-separator rule (`------` → `---`) and the Executive Summary heading level to match the convention used by Designs 1.25/1.27/1.30; and rewriting the header block to the accepted-design style (Status, Document ID, Related Designs with per-plane rationale, companion ADR link). No substantive technical content was changed — all 45 numbered sections, the 29 normative invariants (§41), the 8-phase implementation plan (§39), and all worked examples are preserved as written in revision 1.2.

## Decision

Accept and fold in the Design 1.34 architecture, which establishes **Temporal Versioned Knowledge and Retrieval** as a cross-plane platform capability without introducing a competing ownership model:

- **Ownership stays with existing planes** — Design 1.28 (Ingestion) owns `VersionSeries`/`SourceVersion` and authoritative source-version history; Design 1.25 (Knowledge) owns knowledge derived from a specific source version; Design 1.31 (Search) owns temporal retrieval behavior; Design 1.34 owns only the cross-plane temporal semantics and invariants (§2)
- **Three independent temporal dimensions** — `published_at` (source-declared), `observed_at` (when Synanton learned of it), and `valid_from`/`valid_to` (when it applied), retained separately and never collapsed into one timestamp (§4)
- **Normative time-zone semantics** — canonical UTC instants with retained source representation/provenance, and an explicit `UNKNOWN` validity state rather than an invented interpretation for ambiguous date-only legal statements (§5)
- **`current` is a set, not a single global version** — multiple independently valid sources or overlapping versions within a series are retained and flagged, never silently resolved to one answer (§7–§9)
- **An explicit correction model** — corrections are classified as metadata-only (projection update only) or content-affecting (full Resolutor/Equalix recalculation path) before any downstream processing occurs, so temporal metadata fixes do not force unnecessary recalculation (§14, invariant 17)
- **A normative lifecycle state-transition table** — `ACTIVE`/`SUPERSEDED`/`RETENTION_EXPIRED`/`LEGAL_HOLD`/`DELETED`/`CORRECTED` with explicit allowed transitions; `DELETED` is terminal (§15.1)
- **Temporal eligibility as a pre-ranking search constraint**, joining security eligibility ahead of candidate ranking, with an explicit "search contamination" quality dimension for when a temporally ineligible version is retrieved anyway (§19, §37)
- **Explicit historical-query failure semantics** (`VERSION_NOT_FOUND`, `HISTORY_INCOMPLETE`, `VERSION_DELETED_BY_POLICY`, etc.) so a missing historical state can never silently become a current-state answer (§32)
- **A two-stage temporal index strategy** — `(source_id, as_of) → eligible version(s)` before semantic retrieval — to keep `as_of` query cost independent of full historical scan (§20)
- 29 normative invariants (§41) extending the platform's existing invariant set, and an 8-phase implementation plan (§39) beginning with a Design 1.28 extension

## Consequences

**Enables:**
- Point-in-time retrieval ("what did this say on date X?") and change-comparison queries as first-class platform primitives rather than application-specific workarounds
- Legal/regulatory reproducibility: a historical query's result remains stable as new versions arrive, and corrections are auditable as explicit source-state facts rather than silent mutation
- A measurable "temporal contamination rate" as a retrieval-quality metric, extending the existing evaluation discipline established for search (Design 1.31)
- Reuse of existing plane boundaries and the Design 1.27 eventing substrate — no new ownership model, storage engine, or asynchronous mechanism is introduced

**Requires:**
- Extending Design 1.28's domain model with `VersionSeries`, `published_at`, `valid_from`/`valid_to`, `supersedes_version_id`, and lifecycle state (Phase 1)
- Ensuring Knowledge (1.25) provenance references the specific source version it was derived from (Phase 2)
- Temporal candidate eligibility in Search (1.31), evaluated before ranking, alongside the existing security-eligibility constraint (Phase 4)
- Correction classification (metadata-only vs. content-affecting) wired into the existing Resolutor/Equalix recalculation path from Design 1.25 (Phase 6)
- Dedicated temporal-retrieval evaluation (Version Accuracy, Temporal Recall@K, Temporal Contamination Rate, Historical Availability Rate) (Phase 7)

**Trade-offs:**
- An 8-phase implementation plan must land before the capability is usable end-to-end; none of it has started, and it depends on Designs 1.27 and 1.28 (already accepted but also unimplemented) being built first
- Legal/domain applicability (jurisdiction, precedence, transitional provisions) is explicitly out of scope — Design 1.34 only guarantees correct temporal *eligibility*, not legal *applicability*, leaving a real gap between "which versions were valid" and "which version governs" that a future domain-specific resolver must fill (§10, §42)
- Several implementation choices are deliberately left open pending benchmark data (temporal cache bucket granularity, in-place-update vs. projection-generation-rebuild threshold, exact p95 targets) rather than fixed in advance (§42)

## Implementation Status

Not started — architecture accepted, no implementation exists yet. Per §39, implementation should begin with the Design 1.28 (Ingestion) extension (Phase 1) and should not be attempted ahead of Designs 1.27 and 1.28 themselves reaching implementation, consistent with the platform-wide implementation sequence in [`synanton-platform-architecture-1.0.md`](../synanton-platform-architecture-1.0.md) §13.
