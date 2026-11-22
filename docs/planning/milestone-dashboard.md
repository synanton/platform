# Milestone dashboard

**Status:** Restart-resilient live vertical slice achieved. Graph-contract formalization and reconciliation migration in progress. Full adapter conformance and activation fault-matrix verification outstanding.

This is the PR-backed view of what is implemented and what is still open. It is
not a feature roadmap. Current main: see [main commit history](https://github.com/synanton/platform/commits/main); this dashboard intentionally does not pin a merge hash.

**Last updated:** 2026-11-13 by PR #688. Status reflects main at the time of that edit; see commit history for changes since.

## Phase 2b status model

| State | Meaning | Today |
|---|---|---|
| **Write safety** | Stale graph mutations rejected after fence advancement | Substantially implemented (#644/#648/#665/#672); #666 abort/retry gate closed (#681), with the concurrent fence/abort interaction proven in #685; #667 remains partial on per-source outcome/drift/authorized-generation metrics, but `ABORT_RECOVERABLE` is now emitted and `RECONCILIATION_FAILURE` is deliberately owned by reconciliation status (#657) |
| **Activation correctness** | A durable record identifies the active source revision and supports recovery | Activation record/store (#669) and fence-guarded `ActivateRevision` (#684) implemented; no ingestion-originated activation yet (#654 wiring) |
| **Read visibility correctness** | Every read path exposes only knowledge valid under that activation state | Not implemented (#668, #655/#656, #649b) |

Write safety is substantially implemented. The activation mechanism exists, but no revision is activated through the production ingestion path. That is why the temporary-visibility window remains **operationally unbounded**: the structural unlock is #654 wiring, not #655 alone.

**User-visible today:** a recently superseded source revision may still appear as current in search results, entity/assertion views, and evidence panels. This is the documented temporary-visibility window, not a filtering bug. It remains operationally unbounded because the activation core is not invoked from ingestion and no reader is generation-aware.

**Binding gate today:** #654 — activation is not invoked by ingestion. Once #654 lands, the binding gate becomes #655/#656 — readers are not generation-aware.

Sequencing: #666 and #669 are closed. #654 remains open for generation assignment at revision acceptance, projection-transition wiring, and ingestion-originated `ActivateRevision`; its named observables are pinned in the ticket. #655 and #656 are sequential to merge but must be drafted and reviewed together against the finalized activation/search contracts. No single ticket is an end-to-end lifecycle delivery; #654 + #655 + #656 + #649b are required, and #658 proves them.

## Implemented and evidenced

| Capability | Implementation | Acceptance evidence | Remaining blocker |
|---|---|---|---|
| Document ingestion, search, provenance | #542/#555/#557, #537 UI E2E | `docs/acceptance/live-dense-2026-11-06.md`; vertical-slice E2E | — |
| Extraction gold + regex/LLM baseline | #558/#559, #562/#563/#567 | Frozen 5-doc gold; entity F1 0.182 regex / 0.618 Qwen; ADR-022 | #593 provider gate |
| Durable resolution lifecycle | #571–#575, #579–#581 | Resolution fixture; V22–V24 migrations | — |
| Canonical validation + promotion | #575, #579–#581 | VALID-only promotion; evidence; concurrency fencing | #584 retraction policy |
| Recoverable consumers + reconciliation | #582/#585/#595, #638 | Combined real-store matrix; live K8s rerun; #592/#589 closed | — |
| Search-generation provenance | #577/#596 | V26; candidate/decision/Cassandra/Neo4j propagation | — |
| Bounded Entity Graph API | #597 | `GET /v1/entities/{id}/neighbors`; 50-cap; evidence | — |
| Entity Graph UI | #598 | Directional SVG, evidence panel, mocked Playwright | #599 live E2E (closed) |
| Local RAKG activation | #601 | Compose smoke: upload → promotion → graph + evidence | — |
| K8s RAKG overlay | #605 | Neo4j/Relix/Syntology/network policies | Post-#592 rerun |
| Live K8s Entity Graph E2E | #608/#606 | `docs/acceptance/k8s-activation-2026-11-11.md`; 25.1s | Full #589 matrix |
| Restart-safe graph persistence | #610/#612, ADR-025 | 122 Relix tests; repeated/partial/interrupted load; simultaneous Synquest+Relix restart; no duplicate edges | #592 seam migration |
| Reconciliation port migration | #617 | Increment A merged; seam removed; Relix tests | #587 Increments B/C/D |
| Graph identity + capabilities | #618 (in review) | `GraphIdentity`, `GraphCapabilities`, capability-gated startup; tests pending review | conformance suite; broader port adoption |

## Open gates

- **#587** — Increment A (seam removal, #617) is merged. Increments B/C are in
  progress on this branch: explicit `GraphIdentity`, `GraphCapabilities`, and
  startup capability checks. The broader port adoption, conformance suite,
  transaction guarantees, and rule-5 lint remain.
- **#592** — post-#587 reconciliation migration verification; cannot close until
  #587 is complete.
- **#589** — full combined consumer/reconciler concurrency/lost-response matrix
  and post-#592 rerun.
- **#609/#611** — extraction quality and post-activation reliability epic;
  deferred until the source-change lifecycle is established.
- **#613–#615** — CI follow-ups (ZWJ/ZWNJ context, job timeouts, contract update
  ordering).

## CI state

- PR #616 and #617 are merged. #616 tightens docs-ru/prometheus triggers, adds
  PR concurrency cancellation, and records the test matrix in
  `docs/ci/coverage.md`.
- Explicitly excluded from `buildAll` for runner memory:
  `content-cache-postgres`, `synvault-postgres`, `synquest-postgres`.

## What not to do

Phase 2a is closed. Phase 2b (source-change lifecycle) is the active track. Estimate:
~55–60% complete, 12–26 engineering days, assuming #654 wiring does not reveal
ingestion-pipeline rework; otherwise revert to ~50–55% / 15–29 days. The revision
reflects closed #666/#669 evidence and adopted contract gates, not governance
progress. Readiness depends on #654 wiring, the read-path inventory (#668), and
the #655 support-ledger/#656 failure model. Do not start Analytics, Resolutor, UI
#25, extraction features, or new connectors before source-change visibility and
the maintenance E2E are complete. Preserve the frozen extraction baseline and
restart-safety evidence.
