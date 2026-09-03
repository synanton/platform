# Synanton Platform - Architecture (v1.21)

> **Document type:** Engineering reference (pointer + extraction-plane addendum)
> **Version:** 1.21
> **Date:** 2026-08-26
> **Status:** Superseded as the live pointer - see [`synanton-design-1.22.md`](./synanton-design-1.22.md). Part IX (extraction plane) text remains authoritative here.
> **Audience:** Architects, module owners, SREs, security engineers

This document is the **Part IX** extraction-plane pointer. The live architecture pointer is [`synanton-design-1.22.md`](./synanton-design-1.22.md).

| Layer | Where to read it |
|---|---|
| Baseline (helper/wizard, query/ingest core, DR, security) | [`synanton-design-1.19.md`](synanton-design-1.19.md) - **superseded as “the” current doc**, still the merged baseline for Parts I–VII |
| GPU Execution Plane isolation (Part VIII, §50–§64) | [`synanton-design-1.20.md`](synanton-design-1.20.md) |
| Structured Content Extraction Plane (Part IX) | This document + [`../proposals/v1.21/`](../proposals/v1.21/) |

Do not treat 1.19 as the live system description. GPU isolation and the extraction contract are in force.

---

## What's new in v1.21

v1.21 adds a **Structured Content Extraction Plane** behind `synanton.extraction.v1`. Topology (embedded, sidecar, or cluster) MUST NOT change the contract.

| # | Change | Home |
|---|---|---|
| 1 | Extraction is a platform contract, not a processor | Part IX below; `content_extractor` |
| 2 | Byte-identical proto mirror between `platform` and `content_extractor` | `scripts/verify-contract-mirror.sh` |
| 3 | Feature state is explicit (`APPLIED` / `NOT_APPLICABLE` / `UNSUPPORTED` / `FAILED`) | extraction error catalogue |
| 4 | Ingest PoC: ExtractSync → semantic chunks with page/section → BM25 index | `scripts/run-extract-index-poc.sh` |

v1.20 GPU isolation remains in force. `synanton.gpu.v1` is mirrored byte-for-byte between `platform` and `gpu-runtime` (`scripts/verify-gpu-contract-mirror.sh`). Java package is `org.synanton.gpu.v1`; RPCs are `Execute`, `Cancel`, `GetStatus`, `GetCapacity`; errors are the `ErrorReason` catalogue. Until that mirror holds, gpu-runtime must not be treated as a platform GPU server.

---

## Part IX - Structured Content Extraction Plane (summary)

**Invariant:** the platform specifies *what* to extract and under *what constraints*. The plane specifies *how*. Parsers, OCR sidecars, GPUs, queues, and worker topology MUST NOT appear on the contract.

```text
Object store (raw bytes)
        │
        ▼
synanton.extraction.v1  (ExtractSync / async later)
        │
        ▼
DocumentPayload (elements, headings, tables, page boxes)
        │
        ▼
synflux SemanticChunkStage → persist (page/section) → synquest
```

**In scope for the current PoC:** `ExtractSync`, `GetCapabilities`, MinIO source reads, text + PDF adapters, fail-open Tika in synflux when the plane is down or declines a type.

**Out of scope for the PoC (still planned):** async operations, PostgreSQL operation store, dedicated `extraction-client`, SCEP-6 topology proof.

Full contract text: [`../proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md`](../proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md).  
Implementation plan: [`../implementation/content-extraction-plane/INDEX.md`](../../implementation/content-extraction-plane/INDEX.md).

---

## Compatibility

- Rolling from 1.20: extraction client/URL unset keeps Tika-only ingest.
- Rolling from 1.19: GPU client remains off until `gateway.gpu.enabled=true` **and** a gpu-runtime that serves the mirrored `synanton.gpu.v1`.
- Relix graph backends are selected with `relix.graph.connector` (`memory` | `neo4j` | `nebula`); this is an adapter swap, not a design-version break.

---

## How “current” is managed

`docs/VERSION` is `1.22`. [`INDEX.md`](./INDEX.md) names [`synanton-design-1.22.md`](./synanton-design-1.22.md) as authoritative. This file remains the Part IX extraction summary; 1.19 and 1.20 remain as lineage for earlier parts.
