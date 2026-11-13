# Milestone dashboard

**Status:** Phase 2a complete (live K8s gate passed). Phase 2b in progress: write-safety abort/retry gate (#666) closed; activation correctness and read visibility correctness not implemented. Phase 2b planning estimate remains ~50–55% (15–29 engineering days); contract artifacts improved confidence, but remaining lifecycle volume is unchanged.

This is the PR-backed view of what is implemented and what is still open. It is
not a feature roadmap. Current main: see [main commit history](https://github.com/synanton/platform/commits/main); this dashboard intentionally does not pin a merge hash.

**Last updated:** 2026-11-13 by PR #680/#681. Status reflects main at the time of that edit; see commit history for changes since.

## Phase 2b status model

| State | Meaning | Today |
|---|---|---|
| **Write safety** | Stale graph mutations rejected after fence advancement | Substantially implemented (#644/#648/#665/#672); #666 abort/retry gate closed (#681); #667 outcome metrics merged (#680); terminal-attempt counting and `ABORT_RECOVERABLE` metrics still UNENFORCED |
| **Activation correctness** | A durable record identifies the active source revision and supports recovery | Not implemented (#669) |
| **Read visibility correctness** | Every read path exposes only knowledge valid under that activation state | Not implemented (#668, #655/#656, #649b) |

Only write safety is substantially implemented. The temporary-visibility window is documented but **operationally unbounded** until #655/#656.

**User-visible today:** a recently superseded source revision may still appear as current in search results, entity/assertion views, and evidence panels. This is the documented temporary-visibility window, not a filtering bug, and it is operationally unbounded.

Sequencing: #666 (safety) is closed; #654 is next; #668/#669 are drafted/reviewed in parallel with #654 and become hard prerequisites for #655/#656 acceptance.

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
| K8s RAKG overlay | #605/#643 | Neo4j/Relix/Syntology/network policies; post-#592 live rerun passed | — |
| Live K8s Entity Graph E2E | #608/#606/#643 | `docs/acceptance/phase2-589-combined-matrix-2026-11-12.md`; live rerun 29.0s on 8c56ea1d | — |
| Restart-safe graph persistence | #610/#612, ADR-025 | 122 Relix tests; repeated/partial/interrupted load; simultaneous Synquest+Relix restart; no duplicate edges | #650 readiness follow-up |
| Reconciliation port migration | #617/#630/#638 | seam removed; capabilities composition; rule-5 boundary; #630 conformance | #587 separate GraphTransactionPort/composite scope |
| Fence readiness | #650/#660 | Bolt + seven constraints; schema-init; Relix fail-closed; maintenance restarts preserve fence | #670 schema-init failure-mode coverage |
| Cross-store reconciliation | #644/#662 | monotonic/idempotent fence; missing/unavailable/stale-snapshot/divergence outcomes | #657 lifecycle/status supply |
| Runtime promotion fence | #648/#663/#681 | Cassandra pre-check + fence check in Neo4j mutation transaction; post-staging abort rollback and idempotent retry proven on real Neo4j | #655/#656 visibility window |

## Open gates

- **#587** — separate contract scope for `GraphTransactionPort`/composite
  guarantees; not a Phase 2b blocker.
- **#592** — closed: reconciliation migration/capability/lint evidence landed.
- **#589** — closed: combined local real-store matrix + live K8s rerun (#643).
- **Phase 2b** — source-change lifecycle. ADR-029 locked; identity plumbing
  (#646), promotion-row identity (#647), fence readiness (#650), cross-store
  reconciliation (#644), and runtime promotion fence (#648) are merged.
  #649a safety work is closed: delayed-generation-2 (#665), concurrent-generation
  (#672), and abort/retry (#666/#681) regressions merged. #667 outcome metrics
  merged (#680); terminal-attempt counting and `ABORT_RECOVERABLE` metrics remain
  UNENFORCED. #649b is blocked on #655/#656.
- **Known temporary-visibility window** — under the weak invariant, generation 2
  may be committed and briefly visible as current after generation 3 is
  accepted. It is documented but **not operationally closed and has no interim
  bound**; no generation-aware reader/retirement/search filter exists.
  #667 merged and adds observability; #655/#656 close the window.
- **Read-path/activation design** — #668 inventory + per-request current-generation
  resolution; #669 authoritative activation record and projection recovery.
- **2b lifecycle tickets** — staged activation (#654), retirement (#655),
  search replacement (#656), lifecycle API (#657), maintenance E2E (#658).
- **#650** — closed: Neo4j readiness requires Bolt + full fence-schema inventory; Relix startup fails closed. Two maintenance restarts passed; #670 covers failure-mode schema-init coverage.
- **#609/#611** — extraction quality and post-activation reliability epic;
  deferred until the source-change lifecycle is established.
- **#613–#615** — CI follow-ups (ZWJ/ZWNJ context, job timeouts, contract update
  ordering).

## CI state

- PR #616 tightens docs-ru/prometheus triggers, adds PR concurrency cancellation,
  and records the test matrix in `docs/ci/coverage.md`.
- Explicitly excluded from `buildAll` for runner memory:
  `content-cache-postgres`, `synvault-postgres`, `synquest-postgres`.

## What not to do

Phase 2a is closed. Phase 2b (source-change lifecycle) is the active track. Estimate:
~50–55% complete, 15–29 engineering days; readiness forecast depends on the
read-path inventory (#668) and the #655 support-ledger/#656 failure model. Do not
start Analytics, Resolutor, UI #25, extraction features, or new connectors before
source-change visibility and the maintenance E2E are complete. Preserve the
frozen extraction baseline and restart-safety evidence.
