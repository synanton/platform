# ADR-044: Ontology-Grounded Chunk Extraction (OGCE)

**Status:** Accepted (design) — 2026-11-22  
**Implementation status:** Pending; this decision does not claim runtime delivery.  
**Status transition:** The current state remains **Accepted (design) — implementation pending** until the evidence criterion below is met. Transition to **Accepted** only after P2-3 gate measurement completes against a benchmark-validated (not merely qualification-validated or candidate-only) hardware/model/runtime profile and every provisional gate is resolved by a reviewed result or an explicit, evidence-backed revision/retirement decision. Qualification validation under [the six-point checklist in #812](https://github.com/synanton/platform/issues/812) means only that one controlled deterministic measurement can be produced without disturbing production; it is a precondition to benchmarking, not P2-3 benchmark-profile validation. G-10 p95 additionally requires **repeated synthetic-request approval, tracked separately from #814**; one model load and one no-op pass establish observed latency only. Merging P0/P1 code, shipping behind a feature flag, successful #814 qualification, or measuring only a subset of gates on candidate hardware does not trigger the transition. Transition to **Superseded** if P2-3 demonstrates that the design cannot meet the accepted gates and a replacement design is selected through a recorded decision. Until one of these transitions is approved and recorded, retain the current status; do not infer acceptance from implementation progress.
**Basis:** The OGCE proposal was accepted in PR [#811](https://github.com/synanton/platform/pull/811), merged as [`9a475c7`](https://github.com/synanton/platform/commit/9a475c796e31e74a498db4566478ddf1a8705b3d). The proposal addresses the gap between the existing RAKG extraction adapter and a versioned, ontology-derived, independently validated extraction contract.

## Decision

1. **Adopt OGCE as the design for the RAKG extraction path.** The active ontology release is compiled into a versioned extraction schema. The schema digest and ontology release are recorded with prompts, cache identity, and extraction results.
2. **Use JSONL for multi-fact extraction.** Each non-empty line is one independently validated JSON object. Complete valid records preceding a truncated tail are retained; malformed lines and response-level protocol failures are quarantined with distinct reason codes.
3. **Keep semantic work with the model and exact work with deterministic code.** The model proposes semantic surfaces, ontology identifiers, values, and evidence. Deterministic code owns source-span anchoring, offsets, IDs, provenance, token/call accounting, schema validation, relation-shape validation, and deduplication.
4. **Preserve retrieval chunking.** Existing structure-aware 512-token retrieval chunks remain unchanged. Extraction segments are planned inside each retrieval chunk, initially targeting 256–384 tokens with configurable overlap and benchmark comparison against 512-token extraction. Segments must never cross retrieval-chunk boundaries or concatenate separate retrieval chunks.
5. **Bound work and make coverage explicit.** Per-document and per-tenant budgets, GPU-capacity-aware scheduling, deterministic candidate consolidation, and explicit processed/eligible/skipped/failed counts are part of the design. Incomplete coverage is `PARTIAL`; promotion is blocked by default unless an auditable policy override applies. `KNOWLEDGE_READY` requires complete eligible-segment coverage.
6. **Keep logical responsibilities inside existing services.** Schema compilation, window planning, subset selection, validation, quarantine, consolidation, and scheduling are modules/contracts within the existing Syntology, Synflux extraction consumer, extraction-engine, candidate-store, and GraphWriter boundaries. This ADR authorizes no new deployable, service, or queue.
7. **Treat ontology evolution as generation-scoped.** Pin ontology release, schema digest, prompt/model/tokenizer/validator revisions. Replay a changed ontology into a separate generation; do not silently mutate historical facts. Incompatible changes require re-extraction from persisted chunks.
8. **Keep the first release bounded.** Small-model-first routing and document-wide coreference are deferred experiments. They require separate evidence and are not part of P0–P3.
9. **Treat all numerical acceptance gates as provisional engineering hypotheses.** They are not SLAs, contractual guarantees, or achieved results. No gate may be marked met or missed until the GPU, model artifact/revision, runtime, tokenizer, and benchmark protocol are pinned and the measurement is run against the frozen gold. The gate register is [OGCE acceptance gates](../../quality/ogce-gates.md).

## Alternatives considered

| Alternative | Decision | Rationale |
|---|---|---|
| Keep one monolithic JSON response per chunk | Rejected | A truncated response can lose all facts in that response; independently validated JSONL records limit loss to the incomplete tail. |
| Keep retrieval chunks as the extraction unit | Rejected as the only mode | Retrieval and extraction have different context needs. Smaller segments are evaluated within, not instead of, the existing retrieval chunks. |
| Concatenate all chunk outputs into a document-wide Pass-2 prompt | Rejected | Prompt size and output pressure grow with document size; the legacy Pass-2 path is not authoritative for newly scheduled RAKG work. |
| Adopt TrustGraph's character-window chunker | Rejected | It would discard Synanton's structure-aware chunking and page/section provenance without addressing bounded scheduling. |
| Let the model generate authoritative offsets or IDs | Rejected | Exact anchoring, identity, and accounting are deterministic responsibilities. |
| Create a service/deployable for each logical component | Rejected | Logical responsibilities remain modules in existing services unless a separate decision authorizes a deployable boundary. |
| Enable small-model-first or document-wide coreference in the first release | Deferred | Neither is assumed to improve quality/cost; each needs a separate equal-compute evaluation and decision. |

## Consequences

- The RAKG path becomes the target for ontology-grounded, budget-bounded extraction; legacy EnrichStage Pass-2 remains compatibility-only while already-running work drains.
- The implementation is phased P0–P3: foundation/schema and benchmark pinning; extraction pipeline; consolidation/boundary evaluation; migration, rollback, and security sign-off.
- The acceptance-gate register is maintained independently of this ADR. Thresholds remain provisional until hardware-pinned measurement and annotation-ceiling review.
- The design requires observable legacy-drain completion: zero queued legacy jobs, expired legacy cache namespaces, and no legacy scheduling throughout the agreed observation window.
- Quarantine handling must enforce tenant-scoped encryption and isolation, audited read/export/replay access, 30-day default retention (90-day maximum absent approved exception), and cleanup when the associated source generation is deleted. Cleanup must be covered by an automated test.
- Cost/latency has no absolute production threshold until a reference GPU/model/runtime profile is pinned. Comparisons must use equal-compute/token budgets and report quality, calls, tokens, retries, GPU-seconds, queue wait, p50/p95 latency, throughput, and peak KV cache.
- This decision does not switch the default extraction provider, authorize a production rollout, or claim implementation completion. Existing provider policy in ADR-043 remains in force.

## Context

ADR-022 established the pluggable extraction-engine boundary and a frozen-gold baseline. RAKG Phase 2 moved extraction to an asynchronous consumer, but the existing extraction path still needs a machine-validated ontology contract, robust partial-response handling, bounded extraction segmentation and scheduling, and auditable partial coverage. The accepted [Design 1.36 OGCE proposal](../proposals/v1.36/ontology-grounded-chunk-extraction.md) provides the detailed contract and operational rules. This ADR records the decision; the proposal remains the detailed design source.

## References

- [OGCE proposal, v1.36](../proposals/v1.36/ontology-grounded-chunk-extraction.md)
- [PR #811](https://github.com/synanton/platform/pull/811), merge commit `9a475c796e31e74a498db4566478ddf1a8705b3d`
- [ADR-022: Pluggable Knowledge Extraction Pipeline](./adr-022-extraction-engine-architecture.md)
- [RAKG Phase 2 asynchronous extraction consumer](../../planning/extraction-consumer-phase2.md)
- [Extracted-mention graph contract](../../planning/extracted-mention-graph-contract.md)
- [OGCE acceptance-gate register](../../quality/ogce-gates.md)
