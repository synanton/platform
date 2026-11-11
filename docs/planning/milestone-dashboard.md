# Milestone dashboard

**Status:** Restart-resilient live vertical slice achieved. Graph-contract formalization and reconciliation migration in progress. Full adapter conformance and activation fault-matrix verification outstanding.

This is the PR-backed view of what is implemented and what is still open. It is
not a feature roadmap.

## Implemented and evidenced

| Capability | Implementation | Acceptance evidence | Remaining blocker |
|---|---|---|---|
| Document ingestion, search, provenance | #542/#555/#557, #537 UI E2E | `docs/acceptance/live-dense-2026-11-06.md`; vertical-slice E2E | — |
| Extraction gold + regex/LLM baseline | #558/#559, #562/#563/#567 | Frozen 5-doc gold; entity F1 0.182 regex / 0.618 Qwen; ADR-022 | #593 provider gate |
| Durable resolution lifecycle | #571–#575, #579–#581 | Resolution fixture; V22–V24 migrations | — |
| Canonical validation + promotion | #575, #579–#581 | VALID-only promotion; evidence; concurrency fencing | #584 retraction policy |
| Recoverable consumers + reconciliation | #582/#585/#595 | Testcontainers fault injection; lease/fence matrix | #589 full matrix; #592 migration |
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
  deferred until the graph lifecycle gate is closed.
- **#613–#615** — CI follow-ups (ZWJ/ZWNJ context, job timeouts, contract update
  ordering).

## CI state

- PR #616 and #617 are merged. #616 tightens docs-ru/prometheus triggers, adds
  PR concurrency cancellation, and records the test matrix in
  `docs/ci/coverage.md`.
- Explicitly excluded from `buildAll` for runner memory:
  `content-cache-postgres`, `synvault-postgres`, `synquest-postgres`.

## What not to do

Do not start Analytics, Resolutor, UI #25, extraction features, or new connectors
until #587 → #592 → #589 are closed. Preserve the frozen extraction baseline and
the restart-safety evidence.
