# SCEP Migration Tracker Reconciliation

**Status:** Complete (tracker audit; no implementation authorization)  
**Date:** 2026-11-28  
**Tracker:** [#975](https://github.com/synanton/platform/issues/975)  
**Parent:** [#936 — Synthetic Ingestion Benchmark v1](https://github.com/synanton/platform/issues/936)  
**Baseline:** `main` at `1e048d5b` after merged #961–#965, #968, #971, and #972.

## Purpose

Reconcile the remaining Platform SCEP/parser-migration tracker items against the merged
production, persistence, and benchmark-chunking work. This document records evidence-based
dispositions only. It does not authorize production source-span integration, parser removal, or a
new implementation milestone.

## Method

Each open item was checked against current `main` source and merged PR state:

- production call sites and test/evaluation call sites;
- the current parser/enforcement boundary;
- merged production, persistence, and benchmark PRs;
- explicit deferral decisions for production source-span work.

Issue titles and old descriptions were not treated as completion evidence.

## Baseline evidence

| Area | Current state | Evidence |
|---|---|---|
| Production extraction | SCEP-only. `ExtractionStage` builds `ParsedDocument` from the Content Extractor payload and has no local structure parsing. | `java/synflux/src/main/java/org/synanton/synflux/pipeline/stage/ExtractionStage.java:61-108` |
| Production persistence | `PersistStage` consumes `ParsedDocument`; no production `DocumentStructure` consumption remains. | `java/synflux/src/main/java/org/synanton/synflux/pipeline/stage/PersistStage.java:200` |
| Production parser removal | Merged in PR #961 (`9a8f6442`). | `gh pr view 961` |
| Canonical persistence | Merged in PR #963 (`bda6ed72`). | `gh pr view 963` |
| Evidence binding completion | Merged in PR #965 (`c3c833d5`). | `gh pr view 965` |
| Benchmark chunking | Merged in PR #972 (`7af7ff98`); run-v3 remains immutable. | `gh pr view 972` |
| Remaining parser code | `DocumentStructureService`, `PdfDocumentAdapter`, and `MarkdownDocumentAdapter` remain in the main source set but are no longer reachable from production extraction. | `java/synflux/src/main/java/org/synanton/synflux/document/DocumentStructureService.java:21` |
| Enforcement | `checkNoPlatformDocumentExtraction` currently checks only direct Tika imports and dependencies. | `build.gradle.kts:56-79` |

## Dispositions

| Issue | Disposition | Evidence and required follow-up |
|---|---|---|
| [#950](https://github.com/synanton/platform/issues/950) | **Still valid — narrowed to test coverage.** Production implementation is complete. | PR #961 removed the production local parse. `ExtractionStage` is SCEP-only and fails closed on SCEP failure. However, `ExtractionStageProductionPathTest` instruments `DocumentStructureService` only for success and unavailable outcomes, not the full acceptance matrix (unsupported media, incomplete payload, validation failure, terminal failure, retry/error handling). Keep open for the remaining instrumented absence coverage, or split that coverage into a narrowly scoped test issue. |
| [#947](https://github.com/synanton/platform/issues/947) | **Still valid — scope updated.** Production consumers are gone; evaluation/test compatibility remains. | Remove stale claims that production `ExtractionStage`/`PersistStage` still consume `DocumentStructure`. Remaining consumers are tests/evaluation plus main-source compatibility classes used by them: `P1ExitConformanceTest`, `P1FixtureConformanceTest`, `A2FixtureValidationTest`, `EvidenceBinderTest`, `SyntheticFixtureValidationTest`, `DocumentStructureServiceTest`, `RegexBaselineSmoke`, `RegexBaselineA4`, `GpuSemanticEvaluation`, and `ExtractionStageProductionPathTest`. Dependency on #950 is satisfied for production; #948 still needs #947's frozen-reproducibility proof before parser removal. |
| [#948](https://github.com/synanton/platform/issues/948) | **Still valid — dependencies updated.** Removal has not happened. | PDFBox and Markdown adapters remain in `src/main`. #950 no longer blocks this item because production extraction is SCEP-only. #947 must still prove frozen P1/A2/A4 reproducibility before any parser/helper removal. The enforcement allowlist belongs to #949. |
| [#949](https://github.com/synanton/platform/issues/949) | **Still valid — scope narrowed to the actual guard gap.** | The production boundary is now SCEP-only, but `checkNoPlatformDocumentExtraction` still enforces only Tika imports/dependencies. It does not yet detect production PDFBox/Markdown parser implementations or maintain an explicit shrinking allowlist. Keep open for the guardrail and allowlist work; do not claim the current check enforces the full boundary. |
| [#954](https://github.com/synanton/platform/issues/954) | **Intentionally deferred.** Do not revive as part of the benchmark milestone. | Production source-span integration remains deferred until a concrete consumer and separate design gate exist. #974 explicitly excludes reviving #954. Any proposal work must remain separate from run-v4. |
| [#974](https://github.com/synanton/platform/issues/974) | **Gated — preflight only until upstream evidence lands.** | Required gates: `content_extractor` #33 (canonical identity/ranges) and #34 (metadata preamble). #32 (`schema_digest`) remains independent and non-blocking. Preflight plan: [`synthetic-ingestion-run-v4-preflight.md`](synthetic-ingestion-run-v4-preflight.md). |
| [#936](https://github.com/synanton/platform/issues/936) | **Keep open as parent tracker.** | Link #972, #974, #975, and this document. Run-v3 is the current immutable benchmark baseline; run-v4 is the next gated execution. |

## Not authorized

- No production source-span consumer migration.
- No parser removal or production code changes.
- No fixture, gold, ontology, scoring, baseline, or denominator changes.
- No benchmark or semantic-extraction rerun.
- No graph writes or authorization expansion.

## Follow-up boundary

Any genuine remaining implementation gap must be opened as a separate, narrowly scoped issue with
concrete call sites and acceptance tests. The next benchmark execution remains #974 after
`content_extractor` #33 and #34 are delivered.
