# Platform documentation inventory

**Reviewed:** 2026-10-21
**Scope:** Documents that state current platform architecture, implementation status, Phase 1 demo acceptance, or security posture. This is a targeted disposition inventory, not a rewrite or a file-count of the historical design archive.

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
| [`implementation/eventing-workflow-plane/INDEX.md`](implementation/eventing-workflow-plane/INDEX.md) | Current / plan-owned status | Canonical record for Design 1.27 contract-freeze work (#247). |
| [`architecture/archive/`](architecture/archive/) | Useful history / superseded | Retained; older design documents explain decisions and identify their folded/current replacements. No history deleted in this pass. |
| [`architecture/proposals/`](architecture/proposals/) | Historical decisions / mixed status | Retained; accepted proposals resolve into canonical designs/ADRs. Navigation now directs readers to the architecture index for authority. |
| [`VERSION`](VERSION) | Baseline marker / potentially unclear | Retained as the 1.22 baseline-series marker; top-level index clarifies it is not the current capstone version or implementation status. |

## Status vocabulary

Use **Proposed**, **Approved (design)**, **Contract frozen**, **In progress**,
**Implemented**, **Validated (evidence/date)**, and **Superseded**. If evidence
is limited to one deployment or request path, name that scope. Do not use an
architecture checkmark or module presence as a production-readiness claim.

## Component workstreams

These child issues own the component repositories' document inventories and
implementation-specific claims. Their documentation must link back here and
the parent tracker; platform-wide completion remains pending until reviewed
component PRs/dispositions are recorded.

| Repository | Workstream | Local disposition in this checkout |
|---|---|---|
| GPU Runtime | [gpu-runtime#25](https://github.com/synanton/gpu-runtime/issues/25) | [PR #26](https://github.com/synanton/gpu-runtime/pull/26) open; scopes GPU-5 validation to selected 2026-09-25 phase-5 requests and records the later scale-to-zero integration status separately. External review pending. |
| Security Provider | [security-provider#5](https://github.com/synanton/security-provider/issues/5) | [PR #6](https://github.com/synanton/security-provider/pull/6) open; distinguishes internal context from planned tenant-bound issuer and preserves open B0/G0/production gates across README, plan, threat model, and platform status. External review pending. |
| Content Cache / Content Service | [content-service#25](https://github.com/synanton/content-service/issues/25) | [PR #26](https://github.com/synanton/content-service/pull/26) open; dates the 2026-10-14 Core conformance evidence and separates it from this PR's checks, Extended conformance, SLO/load, and end-to-end adoption. External review pending. |
| Content Extractor | [content_extractor#17](https://github.com/synanton/content_extractor/issues/17) | [PR #18](https://github.com/synanton/content_extractor/pull/18) open; format/integration status and superseded proposal notices are documented. External review pending. |

## Validation performed

- Checked the issue-linked Phase 1 evidence and #345's later #357 pass report.
- Cross-checked architecture and security status against platform issues #339,
  #340, #341 and #347, plus component workstream descriptions.
- Internal Markdown links and key setup commands are checked as part of this
  documentation change; see the parent issue/PR validation summary.
