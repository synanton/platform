# Platform documentation inventory

**Reviewed:** 2026-10-23
**Scope:** Documents that state current platform architecture, implementation status, Phase 1 demo acceptance, security posture, or the vertical-slice trust state. This is a targeted disposition inventory, not a rewrite or a file-count of the historical design archive.

## Disposition

| Document | Classification | Disposition / action |
|---|---|---|
| [`../README.md`](../README.md) | Current / normative entry point | Updated Phase 1 acceptance and architecture status; distinguishes code presence from validated capability; links canonical indexes and inventory. |
| [`INDEX.md`](INDEX.md) | Current / navigation | Replaced stale 1.22-only pointers and dead paths with existing entry points; states status vocabulary and sources of truth. |
| [`architecture/INDEX.md`](architecture/INDEX.md) | Current / architecture navigation | Corrected approved-vs-in-progress wording for 1.23, 1.26, 1.27 and 1.32; date refreshed. |
| [`architecture/synanton-platform-architecture-1.0.md`](architecture/synanton-platform-architecture-1.0.md) | Current / normative capstone | Retained as approved architecture; status table now separates design approval, frozen contracts, implementation, and Phase 1 validation. Plane designs remain authoritative within each domain. |
| [`acceptance/p0-ingestion-search-2026-10-21.md`](acceptance/p0-ingestion-search-2026-10-21.md) | Current evidence + historical run snapshot | Retained the pre-#357 failure transcript as history; added the current five-check pass and BM25 acceptance correction with date and tracker. |
| [`demo/README.md`](demo/README.md) | Current / demo scope | Current BM25 path, known limitations, and operating notes; five-check status is supported by #357. |
| [`demo/demo-script.md`](demo/demo-script.md) | Current / operational walkthrough | Explicit prerequisites and reproducibility inputs; demonstrates BM25 only and identifies placeholder auth, absent vector leg, and API gaps. |
| [`security/security-model.md`](security/security-model.md) | Current / normative security contract and status | Corrected scope: proven request-spoofing rejection is limited to the demo path; issuer, identity, and platform-wide data-layer controls remain tracked work. |
| [`implementation/platform-api-plane/INDEX.md`](implementation/platform-api-plane/INDEX.md) | Current / plan-owned status | Canonical record for Design 1.32 implementation gates and blockers; README links here rather than duplicating detailed plan state. |
| [`implementation/eventing-workflow-plane/INDEX.md`](implementation/eventing-workflow-plane/INDEX.md) | Current / plan-owned status | Canonical EWP-0–EWP-6 plan; contract frozen 2026-10-17 (#247), EWP-0 closed under #375, EWP-1 contract implementation in progress. |
| [`implementation/eventing-workflow-plane/EWP-1-contract.md`](implementation/eventing-workflow-plane/EWP-1-contract.md) | Current implementation profile | Records EWP-1 Java fields, JSON compatibility, tenant/security/replay decisions, and strict scope boundary. |
| [`implementation/eventing-workflow-plane/EWP-0-reconciliation.md`](implementation/eventing-workflow-plane/EWP-0-reconciliation.md) | Current audit / decision artifact, complete | Contract-section matrix, code evidence, stable delta IDs, publisher dispositions, EWP-1 scope, and progress-delivery triggers; closure recorded in #375. |
| [`implementation/vertical-slice/INDEX.md`](implementation/vertical-slice/INDEX.md) | Current plan / scope boundary | #253's five demonstrator acceptance criteria are closed; Architecture 1.0 §16's broader cross-plane slice criterion remains open. The "state going in" table is a dated 2026-10-12 snapshot, not a current implementation inventory. |
| [`implementation/vertical-slice/trust-bar.md`](implementation/vertical-slice/trust-bar.md) | Current trust/readiness criteria | Reviewed 2026-10-19; all five questions remain Partial. It records trustworthiness follow-ups after #253's acceptance closure. |
| [`implementation/vertical-slice/gap-register.md`](implementation/vertical-slice/gap-register.md) | Current bounded-gap record | Tracks resolved and open gaps separately (including resolved G-1/#294 and outstanding PostgreSQL live-test gap #287); individual rows own their status. |
| [`implementation/vertical-slice/diagnostic-runbook.md`](implementation/vertical-slice/diagnostic-runbook.md) | Current operational guidance | Ordered diagnostic procedure for failures observed during the October 2026 slice runs; retained alongside the open trust follow-ups. |
| [`architecture/archive/`](architecture/archive/) | Useful history / superseded | Retained; older design documents explain decisions and identify their folded/current replacements. No history deleted in this pass. |
| [`architecture/proposals/`](architecture/proposals/) | Historical decisions / mixed status | Retained; accepted proposals resolve into canonical designs/ADRs. Navigation now directs readers to the architecture index for authority. |
| [`VERSION`](VERSION) | Baseline marker / potentially unclear | Retained as the 1.22 baseline-series marker; top-level index clarifies it is not the current capstone version or implementation status. |

## Status vocabulary

Use **Proposed**, **Approved (design)**, **Contract frozen**, **In progress**,
**Implemented**, **Validated (evidence/date)**, and **Superseded**. If evidence
is limited to one deployment or request path, name that scope. Do not use an
architecture checkmark or module presence as a production-readiness claim.

## Component workstreams

These child issues owned the component repositories' document inventories and
implementation-specific claims. Their merged outcomes are recorded here; all
four linked component workstreams were merged on 2026-10-21 before closure of
the parent tracker.

| Repository | Workstream | Local disposition in this checkout |
|---|---|---|
| GPU Runtime | [gpu-runtime#25](https://github.com/synanton/gpu-runtime/issues/25) | [PR #26](https://github.com/synanton/gpu-runtime/pull/26) merged 2026-10-21; issue closed. |
| Security Provider | [security-provider#5](https://github.com/synanton/security-provider/issues/5) | [PR #6](https://github.com/synanton/security-provider/pull/6) merged 2026-10-21; issue closed. |
| Content Cache / Content Service | [content-service#25](https://github.com/synanton/content-service/issues/25) | [PR #26](https://github.com/synanton/content-service/pull/26) merged 2026-10-21; issue closed. |
| Content Extractor | [content_extractor#17](https://github.com/synanton/content_extractor/issues/17) | [PR #18](https://github.com/synanton/content_extractor/pull/18) merged 2026-10-21; issue closed. |

## Validation performed

- Checked the issue-linked Phase 1 evidence and #345's later #357 pass report.
- Cross-checked architecture and security status against platform issues #339,
  #340, #341 and #347, plus component workstream descriptions.
- Internal Markdown links and key setup commands are checked as part of this
  documentation change; see the parent issue/PR validation summary.
