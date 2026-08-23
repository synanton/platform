# Synanton Platform v1.21 – Structured PDF Parsing PoC – Outcome Decision

------

**Document Type:** Proposal Outcome
**Proposal Version:** 1.21 (draft)
**Outcome Date:** 2026-08-26
**Status:** Approved
**Scope:** As defined in the proposal

------

## 1. Executive Summary

The proposal for **Structured PDF Parsing (Proof of Concept)** has been reviewed by the Architecture Team, Module Owners, and Ingestion Leads. The proposal is **approved for implementation** as described, with a few clarifications and minor additions captured in this outcome document.

The core rationale – that Synanton currently lacks a document‑processing  API and discards all structure early in the pipeline – is fully  accepted. The proposed solution, which introduces a pluggable `DocumentProcessor` SPI, a Synanton‑owned `CanonicalDocument` model, a registry, and an isolated OpenDataLoader adapter, aligns  perfectly with the platform's architectural principles (Lucentrix design rules, separation of concerns, and implementation cloaking).

The PoC is considered **low‑risk** (default‑off, reversible, no schema or API changes) and delivers value  even if OpenDataLoader is ultimately not selected, as the refactoring of `ParseStage` and the new SPI are prerequisites for any future structured extraction work.

------

## 2. Review Outcome

| Decision              | Detail                                                       |
| --------------------- | ------------------------------------------------------------ |
| **Approval Status**   | ✅ **Approved** – proceed with implementation as proposed.    |
| **Requested Changes** | None that affect scope or timeline. The following clarifications and  recommendations are recorded and will be addressed during  implementation: |
|                       | 1. **Error handling for OpenDataLoaderAdapter** – explicitly implement fallback to Tika on any adapter failure  (including timeout, malformed output, or ODL exception) before returning `FAILED`. |
|                       | 2. **Preliminary schema for CanonicalDocument** – provide a minimal JSON/Java example in the module's `README` to align team understanding before coding starts. |
|                       | 3. **Memory considerations** – add a note in the implementation to track heap usage per document  and, if needed, adjust concurrency in a follow‑up (not required for PoC  success). |
|                       | 4. **OpenDataLoader version pin** – use `2.0.0` (latest stable Apache‑2.0 release) and verify license compatibility in `THIRD_PARTY`. |
| **Risk Assessment**   | Low – default‑off, fallback chain, no persistence changes, and reversible. |
| **Timeline**          | 4 weeks as estimated. Implementation plan (PR sequence) approved. |

------

## 3. Resolved Open Questions

All open questions from §9 of the proposal were discussed and resolved:

| Question                                             | Resolution                                                   |
| ---------------------------------------------------- | ------------------------------------------------------------ |
| Should `CanonicalDocument` be persisted in this PoC? | **No.** It remains in‑memory only. This keeps the PoC reversible and avoids a  Cassandra schema migration. Persistence will be considered in a  follow‑up proposal based on PoC results. |
| Is `application/octet-stream` common in practice?    | The registry will use Tika's detector as a fallback when media type is  generic. This is acceptable; the detection overhead is negligible. |
| What ODL version to pin?                             | Pin **version 2.0.0** (Apache‑2.0). The version will be recorded in `ExtractionInfo.processorVersion`. |
| Should Tika fallback be disableable?                 | **No** – the PoC keeps it always‑on. A future production feature may allow quarantine of failing documents; this is out of scope. |
| Does `syntology` want extracted metadata now?        | Deferred until extraction quality is proven. The PoC will collect metrics but not integrate with `syntology` until a follow‑up. |

------

## 4. Implementation Plan (Confirmed)

The four‑week plan and PR sequence are approved with no changes:

| Week | Activities                                                   |
| ---- | ------------------------------------------------------------ |
| 1    | Create `document-processing` module (SPI, model, registry, Tika processor). Unit tests. No behaviour change. |
| 2    | Integrate into `synflux` – `ParseStage` delegation, `ParsedDocument.canonical`, config keys, metrics. Keep flag off. |
| 3    | Implement `document-processing-pdf-odl` adapter – mapping, timeout, temp‑file hygiene. Unit tests with fixture PDFs. |
| 4    | Run comparative ingestion on the books corpus, collect measurements, and produce findings report against acceptance criteria. |

**PR Sequence (approved):**

- **PR‑1:** `document-processing` module (SPI, model, registry, Tika)
- **PR‑2:** `synflux` integration (delegation, config, metrics)
- **PR‑3:** `document-processing-pdf-odl` (adapter and mapping)
- **PR‑4:** Validation harness and final report

------

## 5. Acceptance Criteria (Confirmed)

The PoC is considered successful if **all** of the following are met (as stated in §6.3) **after** the comparative ingestion run:

- ✅ Structured extraction produces correct reading order on ≥90% of non‑scanned PDFs.
- ✅ Headings are extracted with correct nesting on ≥80% of documents with a TOC.
- ✅ Tables retain row/column structure where Tika flattens them.
- ✅ p95 parse latency stays within **5×** of the Tika baseline.
- ✅ No parse failure escalates to an ingestion job failure.
- ✅ Removing the ODL module from the build reverts PDF handling with no code change.
- ✅ Peak heap at parallelism 8 stays within the existing container limit.

**Note:** Failing latency or memory criteria is an *informative* result, not a blocker for the PoC. It will inform follow‑up work on  concurrency control but does not invalidate the architectural approach.

------

## 6. Follow‑up Items (Unblocked)

The following work is explicitly deferred and will be proposed separately after the PoC completes:

- Structure‑aware chunking (depends on measured `CanonicalDocument` output).
- Persisting `CanonicalDocument` to Cassandra (requires schema migration).
- Positional citations and bounding‑box storage.
- Support for EPUB, DOCX, and HTML processors (SPI already accommodates them).
- OCR for scanned PDFs (separate processor evaluation).
- ODL hybrid mode (requires AI backend; evaluate only if deterministic mode is insufficient).
- Tenant‑level processor policies and quarantine workflows (no evidence yet).

------

## 7. Approval Signatures

| Role                         | Name     | Date                                 |
| ---------------------------- | -------- | ------------------------------------ |
| **Lead Architect**           | *[name]* | 2026-08-26                           |
| **synflux Module Owner**     | *[name]* | 2026-08-26                           |
| **Ingestion Lead**           | *[name]* | 2026-08-26                           |
| **GPU Execution Plane Lead** | *[name]* | 2026-08-26 (no impact, acknowledged) |

------

## 8. Decision Record Location

This outcome document will be placed at:

```
docs/proposals/v1.21/decision.md
```

and linked from the proposal itself.

------

**End of Outcome Document**