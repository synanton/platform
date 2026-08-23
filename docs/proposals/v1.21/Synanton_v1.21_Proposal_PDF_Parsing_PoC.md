---
title: "Synanton Platform v1.21 Proposal - Structured PDF Parsing (PoC)"
version: "1.21"
status: "draft"
date: "2026-08-23"
audience: "Architects, module owners, synflux owners, ingestion leads"
---

# Synanton Platform v1.21 Proposal — Structured PDF Parsing (Proof of Concept)

> **Document type:** Enhancement proposal (Proof of Concept)
> **Version:** 1.21 (draft)
> **Date:** 2026-08-23
> **Status:** Proposed
> **Scope:** `synflux` parse stage, new `document-processing` module, PDF processor adapter
> **Related docs:**
> - [synanton-design-1.20.md](../../architecture/synanton-design-1.20.md)
> - [Synanton Document Ingestion Processing](../../../../lucentrix/docs/synanton_ingestion.md) (Lucentrix, v1.0 Proposed)
> - [GPU Execution Plane Implementation Plan v1.21](../../../../gpu-runtime/doc/GPU%20Execution%20Plane%20Implementation%20Plan%20v1.21.md)

---

## 1. Motivation

The Lucentrix ingestion design establishes the principle:

> **Lucentrix knows how to get the content. Synanton knows how to process the content. Processing implementations are cloaked behind Synanton-owned APIs.**

Synanton currently satisfies the first half of that statement and not the second. There is no document-processing API, no canonical document model, and no processor registry. Document parsing is a single hard-coded Apache Tika call inside the ingestion pipeline.

### 1.1 The current implementation

`java/synflux/src/main/java/org/synanton/synflux/pipeline/stage/ParseStage.java:30` reduces every document — PDF, EPUB, DOCX, HTML — to one flat string:

```java
text = TIKA.parseToString(new ByteArrayInputStream(doc.bytes()), metadata);
```

The result is carried in a record whose only content field is a `String` (`ParsedDocument.java:5-9`):

```java
public record ParsedDocument(
    AcquiredDocument acquired,
    String text,
    Map<String, String> metadata
) {}
```

`ChunkStage` then splits that string on whitespace and groups words by count (`ChunkStage.java:34`):

```java
String[] words = doc.text().split("\\s+");
```

### 1.2 What this costs

Structure is discarded before anything downstream can use it. For a 400-page technical book, the pipeline cannot distinguish a chapter heading from a footnote, a table cell from a caption, or page 12 from page 340.

| Lost signal | Downstream capability it blocks |
|---|---|
| Reading order | Multi-column and sidebar text interleaves into nonsense chunks |
| Headings / section hierarchy | Section-aware chunking; breadcrumbs in citations; ontology anchoring |
| Tables | Row/column semantics; numeric fact extraction in `relix` |
| Page numbers, bounding boxes | Citation to a location a human can verify |
| Figure/caption association | Image-adjacent context |
| Document metadata (title, author) | Ontology mapping in `syntology` |

Two consequences matter beyond extraction quality:

1. **Chunk boundaries are arbitrary.** A fixed 400-word window with 50-word overlap cuts mid-sentence and mid-table. Embedding quality is bounded by chunk coherence, so this ceiling propagates into every `synquest` result.
2. **Citations cannot be grounded.** GraphRAG answers can name a document but not a page or region, because no positional data survives the parse stage.

### 1.3 Why now

Three factors make this the right moment for a PoC rather than a full implementation:

- The Lucentrix design is `Proposed`, not built. Fixing the Synanton side now avoids a second migration later.
- The books-library test environment (local filesystem → `synvault` → `synflux` → Cassandra) provides a realistic corpus of long, structurally complex PDFs.
- The v1.20 GPU Execution Plane is not required. Structured PDF extraction is a CPU workload; OpenDataLoader's deterministic mode needs no GPU. This PoC is independent of GPU-plane readiness.

### 1.4 Non-goals for this PoC

This proposal deliberately excludes:

- OCR for scanned PDFs;
- EPUB, DOCX, or HTML processors (the API must accommodate them; the PoC does not implement them);
- structure-aware chunking beyond a single reference strategy;
- replacing Tika as the fallback path;
- Cassandra schema changes;
- any GPU-plane dependency;
- production hardening, tenant-level processor policy, or quarantine workflows.

---

## 2. Summary of Changes

| # | Change | Home in v1.21 |
|---|--------|---------------|
| 1 | New `document-processing` module: `DocumentProcessor` SPI, `ProcessingContext`, `ProcessingResult` | §3.1 |
| 2 | New Synanton-owned `CanonicalDocument` model (blocks, structure, provenance) | §3.2 |
| 3 | New `DocumentProcessorRegistry` selecting a processor by media type | §3.3 |
| 4 | New `OpenDataLoaderAdapter` in an isolated module — cloaked PDF implementation | §3.4 |
| 5 | `TikaDocumentProcessor` retained as universal fallback, now behind the same SPI | §3.5 |
| 6 | `ParseStage` rewritten to delegate to the registry; `ParsedDocument` gains an optional canonical document | §3.6 |
| 7 | New config keys under `synflux.parse.*` (all default to current behaviour) | §3.7 |
| 8 | New metrics `document_parse_*` | §3.8 |

---

## 3. Detailed Design

### 3.0 Module layout

```text
java/document-processing/          (new)
├── api/
│   ├── DocumentProcessor.java
│   ├── ProcessingContext.java
│   ├── ProcessingResult.java
│   ├── ProcessingDiagnostic.java
│   └── DocumentProcessorRegistry.java
├── model/
│   ├── CanonicalDocument.java
│   ├── DocumentBlock.java
│   ├── BlockType.java
│   ├── PageRef.java
│   ├── BoundingBox.java
│   ├── TableContent.java
│   └── ExtractionInfo.java
└── tika/
    └── TikaDocumentProcessor.java

java/document-processing-pdf-odl/  (new, isolated)
└── OpenDataLoaderAdapter.java
```

The dependency direction is one-way: `synflux` → `document-processing` (api + model). `synflux` MUST NOT reference `document-processing-pdf-odl` types in code; the adapter is wired at runtime only. This is what makes the implementation replaceable per Rule 6 of the Lucentrix design.

```text
                synflux ParseStage
                        │
                        ▼
            DocumentProcessorRegistry        ← document-processing (api)
                        │
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
 OpenDataLoader    Tika fallback    Future EPUB/DOCX
   Adapter          Processor         processors
        │
        ▼
   OpenDataLoader             ← document-processing-pdf-odl (isolated)
        │
        ▼
   CanonicalDocument          ← document-processing (model)
```

---

### 3.1 `DocumentProcessor` SPI

**Module:** `document-processing`
**Goal:** Give Synanton a processing contract that is independent of any external parser.

```java
package org.synanton.docprocessing.api;

public interface DocumentProcessor {

    /** Stable identifier, e.g. "pdf-opendataloader", "tika-fallback". */
    String id();

    /** Media types this processor handles, e.g. ["application/pdf"]. */
    Set<String> supportedMediaTypes();

    /**
     * Selection priority. Highest wins for a given media type.
     * The Tika fallback registers at priority 0.
     */
    int priority();

    boolean supports(ContentDescriptor content);

    ProcessingResult process(RawDocument document, ProcessingContext context);
}
```

Supporting types:

```java
public record RawDocument(
    UUID contentRefId,
    String tenantId,
    byte[] bytes,
    String mediaType,
    String sha256,
    String sourceUri,
    String filename
) {}

public record ProcessingContext(
    String tenantId,
    String jobId,
    Duration timeout,
    boolean structuredExtractionEnabled,
    Map<String, String> options
) {}

public record ProcessingResult(
    Outcome outcome,
    CanonicalDocument canonicalDocument,   // null when outcome == FAILED
    String plainText,                      // always populated; flattened view
    Map<String, String> extractedMetadata,
    List<ProcessingDiagnostic> diagnostics,
    ExtractionInfo extractionInfo
) {
    public enum Outcome { SUCCESS, PARTIAL, FAILED }
}
```

`plainText` is always populated, including on `PARTIAL`. This is what preserves backward compatibility: `ChunkStage` continues to work unchanged whether structured extraction succeeded, degraded, or failed.

**Failure boundary.** Per §20 of the Lucentrix design, a processor failure MUST NOT fail the ingestion job. `ParseStage` catches processor exceptions, records a diagnostic, and falls back — the same contract `ParseStage` already honours today with its `catch (Exception e)` at `ParseStage.java:34`.

---

### 3.2 `CanonicalDocument` model

**Module:** `document-processing`
**Goal:** A Synanton-owned representation. Per Rule 5, OpenDataLoader's JSON schema never becomes the canonical model.

```java
public record CanonicalDocument(
    UUID contentRefId,
    DocumentMetadata metadata,      // title, author, language, pageCount
    List<DocumentBlock> blocks,     // flat, in reading order
    ExtractionInfo extraction
) {}

public record DocumentBlock(
    int ordinal,                    // global reading-order position
    BlockType type,
    int level,                      // heading depth; 0 for non-headings
    String text,                    // normalized text, empty for images
    PageRef page,
    BoundingBox bbox,               // nullable
    TableContent table,             // non-null only when type == TABLE
    Integer parentOrdinal           // section nesting; nullable
) {}

public enum BlockType {
    HEADING, PARAGRAPH, LIST_ITEM, TABLE, CAPTION,
    FOOTNOTE, IMAGE, FORMULA, CODE, HEADER_FOOTER, UNKNOWN
}

public record TableContent(
    List<String> headers,
    List<List<String>> rows,
    String markdown        // rendered form for embedding
) {}

public record ExtractionInfo(
    String processorId,
    String processorVersion,
    int canonicalSchemaVersion,
    Instant startedAt,
    Instant completedAt,
    boolean structured     // false when produced by the flat fallback
) {}
```

Two deliberate design decisions:

**Flat block list, not a tree.** Nesting is expressed by `parentOrdinal` and `level`. A flat list with explicit ordinals keeps reading order unambiguous, serializes cleanly to JSON, and lets a chunker iterate without tree traversal. Section hierarchy is recoverable when needed.

**`canonicalSchemaVersion` is explicit.** Reprocessing (Lucentrix design §22) requires distinguishing "extracted by an old processor" from "extracted into an old schema". Both are recorded.

---

### 3.3 `DocumentProcessorRegistry`

**Module:** `document-processing`
**Goal:** Synanton routes by document-processing concerns, independent of how Lucentrix routed by source concerns.

```java
public class DocumentProcessorRegistry {

    private final List<DocumentProcessor> processors;   // Spring-injected
    private final DocumentProcessor fallback;

    /** Highest-priority processor whose supports() accepts the content. */
    public DocumentProcessor select(ContentDescriptor content) { ... }
}
```

Selection is media-type + priority, resolved at runtime from whichever processors are on the classpath:

| Media type | Registered processors | Selected |
|---|---|---|
| `application/pdf` | `pdf-opendataloader` (100), `tika-fallback` (0) | `pdf-opendataloader` |
| `application/pdf` (adapter absent) | `tika-fallback` (0) | `tika-fallback` |
| `application/epub+zip` | `tika-fallback` (0) | `tika-fallback` |
| anything else | `tika-fallback` (0) | `tika-fallback` |

Dropping the ODL module from the build reverts PDF handling to today's behaviour with no code change — the substitution test for the cloaking approach.

**Media type source.** `AcquiredDocument.mimeType()` (`AcquiredDocument.java:11`) is already populated upstream. When it is absent or generic (`application/octet-stream`), the registry falls back to Tika's detector — detection, not full parsing.

---

### 3.4 `OpenDataLoaderAdapter`

**Module:** `document-processing-pdf-odl` (isolated)
**Goal:** Translate OpenDataLoader output into `CanonicalDocument`.

[OpenDataLoader PDF](https://github.com/opendataloader-project/opendataloader-pdf) is Apache-2.0, Java 11+, and runs fully locally with no GPU. It emits JSON with bounding boxes per element, plus Markdown and HTML. It ranked first (0.907 overall) in the [published open-source extraction benchmarks](https://pdfa.org/opendataloader-pdf-v20-tops-open-source-pdf-benchmarks-in-pdf-data-loading/) for reading order, tables, and heading inference.

Coordinates:

```kotlin
// gradle/libs.versions.toml
opendataloader     = "2.x"   // pin exact version at implementation time
opendataloader-pdf = { module = "org.opendataloader:opendataloader-pdf-core",
                       version.ref = "opendataloader" }
```

The adapter's responsibilities, and only these:

```text
RawDocument bytes
      │
      ▼
write to temp file (ODL is file-oriented)
      │
      ▼
invoke ODL → JSON with bounding boxes
      │
      ▼
map ODL elements → List<DocumentBlock>
      │
      ├── ODL heading      → HEADING (level from ODL depth)
      ├── ODL paragraph    → PARAGRAPH
      ├── ODL table        → TABLE + TableContent (+ markdown)
      ├── ODL list item    → LIST_ITEM
      ├── ODL caption      → CAPTION
      ├── ODL image        → IMAGE (bbox only)
      └── unrecognized     → UNKNOWN (never dropped silently)
      │
      ▼
flatten blocks → plainText
      │
      ▼
ProcessingResult
```

**Constraints the PoC must respect:**

- **Deterministic mode only.** ODL's optional hybrid mode requires a separately installed local AI backend on port 5002. Out of scope; the PoC runs ODL's offline deterministic path.
- **Temp file hygiene.** Bytes are written to a temp file and deleted in a `finally` block. `synvault` remains the immutable source of truth (Rule 3); the temp file is scratch.
- **Timeout enforcement.** A large or malformed PDF must not hang an ingestion worker. `ProcessingContext.timeout` is enforced, and expiry yields `FAILED` with a diagnostic, not a hung thread.
- **Unknown elements are mapped, not dropped.** `UNKNOWN` with text preserved keeps extraction lossless even where the mapping is incomplete.
- **Memory.** `IngestionJobRunner` runs documents in parallel across a pool sized by `synflux.ingest.parallelism` (`IngestionJobRunner.java:74`). Structured extraction of a large PDF is heavier than a Tika text pass; §6 covers measuring this.

---

### 3.5 `TikaDocumentProcessor`

**Module:** `document-processing`
**Goal:** Preserve today's behaviour as an explicit, registered fallback rather than an implicit hard-coded call.

This is a direct lift of the existing `ParseStage` logic behind the SPI: `supportedMediaTypes()` returns `["*/*"]`, `priority()` returns `0`, and the result carries `plainText` with `CanonicalDocument` reduced to a single `PARAGRAPH` block and `ExtractionInfo.structured = false`.

The `structured` flag is what lets downstream stages and operators tell a genuine structured extraction from a flat fallback without inspecting block counts.

---

### 3.6 `ParseStage` and `ParsedDocument` changes

**Module:** `synflux`

`ParsedDocument` gains one nullable field. Existing accessors are untouched:

```java
public record ParsedDocument(
    AcquiredDocument acquired,
    String text,                        // unchanged — ChunkStage still uses this
    Map<String, String> metadata,       // unchanged
    CanonicalDocument canonical         // new; null when unavailable
) {}
```

`ChunkStage.java:34` continues to read `doc.text()` and needs no change in this PoC. Structure-aware chunking is a follow-up (§8), enabled by this data being present.

`ParseStage` becomes a delegation with an explicit fallback ladder:

```text
ParseStage.apply(AcquiredDocument, StageContext)
        │
        ▼
registry.select(mediaType)
        │
        ▼
processor.process(rawDocument, context)
        │
   ┌────┴─────────────────┬──────────────────┐
   ▼                      ▼                  ▼
SUCCESS / PARTIAL      FAILED            exception
   │                      │                  │
   ▼                      ▼                  ▼
canonical + text     Tika fallback      Tika fallback
                          │                  │
                          ▼                  ▼
                     text, canonical=null (+ diagnostic)
```

An ingestion job fails only if the Tika fallback also fails — matching current behaviour, where a parse failure logs a warning and yields empty text (`ParseStage.java:34-36`).

**Idempotency is unaffected.** `IngestionJobRunner.java:112-123` keys skip decisions on `contentRefId` and manifest `state`. Those semantics do not change. Note that a document already at `EMBEDDED` will be skipped on re-run, so PoC comparisons must use a distinct tenant or a cleared keyspace (§6.1).

---

### 3.7 Configuration

All keys default to current behaviour. With `structured-extraction.enabled=false`, the pipeline is byte-for-byte equivalent to today.

```yaml
synflux:
  parse:
    structured-extraction:
      enabled: false          # PoC opt-in
      timeout: 120s
      max-document-size-bytes: 104857600   # 100 MB; larger → fallback
    processors:
      pdf:
        id: pdf-opendataloader
        enabled: true
      fallback:
        id: tika-fallback     # always registered; not disableable
```

`max-document-size-bytes` is a separate guard from `synflux.ingest.maxFileSizeBytes`: acquisition may permit a document that structured extraction should not attempt.

---

### 3.8 Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `document_parse_total` | Counter | `processor_id`, `media_type`, `outcome` | Parse attempts by outcome |
| `document_parse_duration_seconds` | Histogram | `processor_id`, `media_type` | Parse latency |
| `document_parse_fallback_total` | Counter | `from_processor`, `reason` | Fallbacks to Tika |
| `document_parse_blocks` | Histogram | `processor_id`, `block_type` | Blocks extracted per document |
| `document_parse_bytes` | Histogram | `processor_id` | Input document size |

Label cardinality stays low: `processor_id` and `media_type` are bounded small sets. Per §62 of the v1.20 design, `tenant_id` is not a metric label here.

---

## 4. Impact on Existing Modules / Sections

| Section / Module | Changes |
|---|---|
| **§17 `synflux`** | `ParseStage` delegates to the registry; `ParsedDocument` gains nullable `canonical`; new `synflux.parse.*` config; new dependency on `document-processing` |
| **§5 Module Map** | Two new modules: `document-processing`, `document-processing-pdf-odl` |
| **§29 Content Adapter SPI** | Unaffected. `ContentAdapter` covers content *acquisition*; `DocumentProcessor` covers *processing*. Distinct concerns, deliberately separate SPIs |
| **§16 `synvault`** | No change. Raw bytes remain immutable and authoritative (Rule 3) |
| **§36 Cassandra schema** | **No change in this PoC.** `CanonicalDocument` is in-memory only, consumed by `ChunkStage` within the same job. Persisting it is a follow-up (§8) |
| **§6 Ingestion Flow** | Parse step gains processor selection and a fallback path; stage sequence unchanged |
| **§45 Observability** | Five new `document_parse_*` metrics |
| **Part VIII GPU plane** | **No impact.** ODL deterministic mode is CPU-only; this PoC neither depends on nor blocks GPU-plane work |
| **Lucentrix** | **No change.** Rule 1 holds: connectors retrieve and describe, never parse |

**Licensing.** ODL is Apache-2.0, matching the platform. Contained in an isolated module regardless.

---

## 5. Backward Compatibility & Upgrade Path

The PoC is non-breaking by construction:

- **Default off.** `synflux.parse.structured-extraction.enabled=false` yields today's behaviour.
- **No public API change.** No REST/gRPC contract, Kafka schema, or Cassandra schema is modified.
- **Additive record field.** `ParsedDocument.canonical` is nullable; `text` and `metadata` are unchanged. `ChunkStage`, `EnrichStage`, `EmbedStage`, and `PersistStage` need no modification.
- **Reversible.** Setting the flag to `false`, or removing the ODL module from the build, restores the Tika path.

Existing `ParsedDocument` constructor calls must be updated for the new component — a mechanical compile-time change confined to `synflux` and its tests (`ChunkStageTest`, `EnrichStageTest`, `EmbedStageTest`).

**Data already ingested** is unaffected: manifests, chunks, and embeddings from Tika-parsed runs remain valid. Documents re-ingested under structured extraction produce different chunk boundaries and therefore different chunk hashes — expected, and the reason §6.1 uses a separate tenant for comparison.

---

## 6. Validation Plan

The PoC's purpose is a decision, so it must produce evidence. Environment: the two-node books-library setup in [`gpu-runtime/doc/TEST_ENVIRONMENT_SETUP.md`](../../../../gpu-runtime/doc/TEST_ENVIRONMENT_SETUP.md), which already runs local filesystem → `synvault` → `synflux` → Cassandra.

### 6.1 Comparative ingestion

Ingest the same corpus twice into two tenants, changing only the flag:

```text
tenant = books-tika        structured-extraction.enabled = false
tenant = books-structured  structured-extraction.enabled = true
```

Separate tenants are required because `IngestionJobRunner.java:112-123` skips documents already at a terminal manifest state.

Corpus: 20–30 PDFs deliberately spanning easy and hard cases — single-column prose, multi-column academic papers, table-heavy references, PDFs with code listings, and at least one scanned document (expected to degrade; ODL deterministic mode does no OCR).

### 6.2 Measurements

| Question | Measurement |
|---|---|
| Does structure survive? | Block counts by type; heading levels vs. the book's actual table of contents |
| Is reading order correct? | Manual review of extracted order on multi-column documents |
| Are tables usable? | Row/column fidelity on a sample, vs. Tika's flattened output |
| What does it cost? | `document_parse_duration_seconds` p50/p95/p99, both tenants |
| What is the memory profile? | Peak heap at `synflux.ingest.parallelism` = 4 and 8 |
| How often does it degrade? | `document_parse_fallback_total` by reason |
| Does chunk coherence improve? | Sample 50 chunks per tenant; count mid-sentence and mid-table breaks |

### 6.3 Acceptance criteria

The PoC succeeds if all hold on the test corpus:

- □ Structured extraction produces correct reading order on ≥90% of non-scanned PDFs.
- □ Headings are extracted with correct nesting on ≥80% of documents with a table of contents.
- □ Tables retain row/column structure where Tika flattens them.
- □ p95 parse latency stays within 5× the Tika baseline.
- □ No parse failure escalates to an ingestion job failure.
- □ Removing the ODL module reverts PDF handling with no code change.
- □ Peak heap at parallelism 8 stays within the existing container limit.

Failing the latency or memory criterion is an informative result, not a dead end: it argues for parse concurrency separate from `synflux.ingest.parallelism`, which §8 covers.

---

## 7. Implementation Plan

| Week | Tasks |
|---|---|
| 1 | `document-processing` module: SPI, `CanonicalDocument`, registry. `TikaDocumentProcessor` lifted from `ParseStage`. Unit tests. No behaviour change yet. |
| 2 | `ParseStage` delegation, `ParsedDocument.canonical`, config keys, metrics. Existing `synflux` tests updated and green with the flag off. |
| 3 | `document-processing-pdf-odl`: adapter, ODL→`CanonicalDocument` mapping, timeout and temp-file handling. Unit tests against fixture PDFs. |
| 4 | Comparative ingestion (§6.1) on the books corpus; collect measurements; write findings against §6.3. |

Four weeks, one developer. Weeks 1–2 are net-positive refactoring even if the ODL evaluation ends in rejection: the SPI, canonical model, and registry are prerequisites for any processor, and they remove a hard-coded parser from the pipeline.

**Suggested PR sequence:**

| PR | Scope |
|---|---|
| PR-1 | `document-processing` module — SPI, model, registry, Tika processor |
| PR-2 | `synflux` integration — `ParseStage` delegation, config, metrics (flag off) |
| PR-3 | `document-processing-pdf-odl` — adapter and mapping |
| PR-4 | Validation harness and findings report |

---

## 8. Follow-Up Work (Out of Scope)

Deliberately excluded here, unblocked by it:

| Item | Why it waits |
|---|---|
| Structure-aware chunking | Needs measured `CanonicalDocument` output to design boundaries well |
| Persisting `CanonicalDocument` | Requires a Cassandra schema decision, hence a schema-migration proposal |
| Positional citations | Depends on persisted bounding boxes |
| EPUB/DOCX processors | The SPI accommodates them; the books corpus contains EPUBs |
| OCR for scanned PDFs | Separate processor, separate evaluation |
| ODL hybrid mode | Needs a local AI backend; evaluate only if deterministic mode proves insufficient |
| Parse-stage concurrency control | Only if §6.2 shows memory pressure |
| Tenant-level processor policy | No evidence yet that tenants need different processors |

---

## 9. Open Questions

1. **Should `CanonicalDocument` be persisted in this PoC?** Recommendation: no. Keeping it in-memory avoids a schema migration and keeps the PoC reversible. It does mean a re-parse is needed to iterate on chunking — acceptable at PoC scale, not at production scale.
2. **Is `application/octet-stream` common in practice from the filesystem connector?** Determines how much the registry's detection fallback matters. Answerable from the first ingestion run.
3. **What is the right ODL version to pin?** v2.x is Apache-2.0 (pre-2.0 was MPL-2.0). Pin an exact version at implementation time and record it in `ExtractionInfo.processorVersion`.
4. **Should the Tika fallback be disableable?** The PoC keeps it always-on. A future strict mode — where a PDF that ODL cannot parse is quarantined rather than flattened — may be preferable in production, per Lucentrix design §20.
5. **Does `syntology` want extracted document metadata now?** Title and author become available for ontology mapping. Deferred until the extraction quality is known.

---

## 10. Conclusion

Synanton's parse stage discards document structure before any downstream stage can use it, which caps chunk quality, blocks table semantics, and makes verifiable citations impossible. This proposal introduces the missing Synanton-owned processing layer — a `DocumentProcessor` SPI, a canonical document model, and a processor registry — and evaluates OpenDataLoader as the first cloaked PDF implementation behind it.

The design follows the Lucentrix ingestion architecture's rules directly: Lucentrix is untouched (Rule 1), `synvault` remains authoritative (Rule 3), Synanton owns the processing API (Rule 4), ODL is one implementation rather than the canonical model (Rule 5), and dropping the adapter module reverts the pipeline with no code change (Rule 6).

Cost is four weeks for one developer, and weeks 1–2 pay for themselves regardless of the ODL verdict. Risk is low: default-off, no schema or public-API change, and a fallback ladder that keeps a parse failure from failing an ingestion job.

**Next steps:**

- Review and approve this proposal.
- Confirm the books-library corpus selection for §6.1, including which scanned PDF to include as a known-degraded case.
- Pin the exact OpenDataLoader version and record its license in `THIRD_PARTY`.
- Open PR-1 (`document-processing` module), which is independent of the ODL decision.
- On completion, record the outcome in `docs/proposals/v1.21/decision.md` per the proposal lifecycle.
