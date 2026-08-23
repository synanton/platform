# Synanton Platform - Final Architecture Design (Merged Reference)

> **Document type:** Definitive engineering reference
> **Version:** 1.21
> **Date:** 2026-08-26
> **Status:** Final merged reference (v1.20 baseline + structured document processing)
> **Audience:** Architects, module owners, SREs, ingestion leads, connector authors
> **Philosophy:** Clean-slate · zero legacy · single API surface · no compatibility shims

This document is the **current authoritative entry point** for the Synanton platform design, superseding v1.20 in that role as of 2026-08-26. It builds on v1.20 as the baseline and introduces a platform-owned document-processing layer. All content from v1.20 that is not explicitly modified here remains authoritative and unchanged.

The document-processing architecture is specified in full in the new Part IX (§65–§79) of this document.

### How to read this document

The platform design is a **chain of incremental merged references**. Each version restates its predecessor as authoritative for unchanged areas rather than reproducing it, so this document is the entry point, not the whole design:

| For | Read |
|---|---|
| Document processing — Part IX (§65–§79) | **this document** |
| GPU Execution Plane — Part VIII (§50–§64) | [`synanton-design-1.20.md`](./synanton-design-1.20.md) |
| v1.20 deltas to §1, §3, §4, §5, §23, §26, §45, §47, §48 | [`synanton-design-1.20.md`](./synanton-design-1.20.md) |
| Complete baseline §1–§49 | [`synanton-design-1.19.md`](./synanton-design-1.19.md) |

v1.20 reproduces only the nine Parts I–VII sections it modified, so v1.19 remains the complete baseline for §1–§49. Neither predecessor is archived: both are still authoritative for the sections this document leaves unchanged. See [`INDEX.md`](./INDEX.md).

v1.21 introduces **no** changes to Part VIII. The GPU Execution Plane is unaffected (§67.4).

**Source proposal:** `docs/proposals/v1.21/Synanton_v1.21_Proposal_PDF_Parsing_PoC.md` (2026-08-23)
**Approval record:** `docs/proposals/v1.21/decision.md` (Approved, 2026-08-26)

The approval recorded four clarifications to be resolved in the design. They are resolved normatively in this document:

| Approval item | Resolved in |
|---|---|
| 1. Error handling for the OpenDataLoader adapter | §73 Error Boundaries and Fallback Contract |
| 2. Preliminary `CanonicalDocument` schema | §69 Canonical Document Model |
| 3. Memory considerations | §74 Memory Model and Object Lifetime |
| 4. OpenDataLoader version pin (`2.0.0`) | §72 OpenDataLoader Adapter |

---

## What's new in v1.21

Version 1.21 introduces a **platform-owned document-processing layer** between raw-content acquisition and knowledge processing. The change is architecturally significant but non-breaking: no public REST/gRPC contract changes, no module renames, and no Kafka, Cassandra, or PostgreSQL schema migrations.

Before v1.21, Synanton had no document-processing contract. Parsing was a single hard-coded Apache Tika call inside `synflux`'s parse stage, which reduced every document to a flat string and discarded reading order, headings, tables, and page positions before any downstream stage could use them.

| # | Change | Home in v1.21 |
|---|--------|---------------|
| 1 | New `document-processing` module — `DocumentProcessor` SPI, `ProcessingContext`, `ProcessingResult` | §68 |
| 2 | New Synanton-owned `CanonicalDocument` model with normative schema | §69 |
| 3 | Explicit document-model lifecycle: `CanonicalDocument` authoritative, `ParsedDocument` a stage envelope | §70 |
| 4 | New `DocumentProcessorRegistry` — media-type + priority selection | §71 |
| 5 | New `document-processing-pdf-odl` module — cloaked OpenDataLoader `2.0.0` adapter | §72 |
| 6 | Normative error boundary and fallback ladder; processor failure never fails an ingestion job | §73 |
| 7 | Object-lifetime rules bounding peak heap; envelope projection after chunking | §74 |
| 8 | New config keys under `synflux.parse.*`, all defaulting to v1.20 behaviour | §75 |
| 9 | New `document_parse_*` metrics | §76 |

### Compatibility statement (v1.21)

v1.21 introduces no breaking changes to the primary-platform public API surface:

- **New modules:** `java/document-processing` (SPI + model + Tika processor) and `java/document-processing-pdf-odl` (isolated OpenDataLoader adapter).
- **Modified module:** `synflux` — `ParseStage` delegates to the registry; `ParsedDocument` gains one nullable field.
- **New config keys** (all defaulting to v1.20 behaviour): `synflux.parse.*` (see §75).
- **New metrics:** `document_parse_total`, `document_parse_duration_seconds`, `document_parse_fallback_total`, `document_parse_blocks` (see §76).
- **No REST or gRPC contract changes.**
- **No Kafka schema changes.**
- **No Cassandra schema changes** — `CanonicalDocument` is in-memory only in this scope (§70.4).
- **No PostgreSQL schema changes.**
- **No GPU Execution Plane dependency** — structured PDF extraction is a CPU workload (§67.4).

Rolling upgrade from v1.20 is safe: structured extraction is disabled until `synflux.parse.structured-extraction-enabled=true`. With the flag off, the parse stage is behaviourally identical to v1.20.

---

# Part IX — Document Processing

## §65. Purpose and Position in the Platform

The Lucentrix ingestion architecture establishes the division of responsibility:

> **Lucentrix knows how to get the content. Synanton knows how to process the content. Processing implementations are cloaked behind Synanton-owned APIs.**

Before v1.21 the platform satisfied the first clause only. Part IX defines the second and third: a platform-owned processing contract, a platform-owned document model, and a cloaking boundary that keeps any specific parser replaceable.

Document processing sits between raw-content preservation and knowledge processing. It answers **"what is in this document?"** — extraction, structure, normalization. It does not answer **"what does it mean?"** — chunking, entities, relationships, ontology, and indexing remain with the knowledge-processing stages defined in Parts II–IV.

```text
   synvault            document processing         knowledge processing
  ──────────          ─────────────────────       ──────────────────────
  raw bytes     →     what is in it?         →    what does it mean?
  provenance          structure, reading           chunk, enrich, embed,
  versions            order, tables, pages         ontology, index
```

The boundary matters because the two sides have different failure semantics, different resource profiles, and different rates of change. Extraction quality improves as parsers improve; knowledge processing changes as the platform's semantics change. Part IX keeps those independent.

---

## §66. Baseline: What v1.20 Did

The v1.20 parse stage was a single Tika call producing a flat string:

```java
// v1.20 — synflux ParseStage
text = TIKA.parseToString(new ByteArrayInputStream(doc.bytes()), metadata);
```

`ParsedDocument` carried content as a bare `String`, and the chunk stage split that string on whitespace, grouping words by count. Structure was destroyed before any consumer could observe it.

Consequences that v1.21 addresses:

| Lost signal | Capability it blocked |
|---|---|
| Reading order | Multi-column and sidebar text interleaved into incoherent chunks |
| Headings, section hierarchy | Section-aware chunking; citation breadcrumbs; ontology anchoring |
| Tables | Row/column semantics; numeric fact extraction in `relix` |
| Page numbers, bounding boxes | Citations to a location a human can verify |
| Figure/caption association | Image-adjacent context |
| Document metadata | Title/author mapping in `syntology` |

Two second-order effects are worth naming, because they set ceilings rather than merely losing detail. Chunk boundaries were arbitrary — a fixed window cuts mid-sentence and mid-table, and embedding quality is bounded by chunk coherence, so the ceiling propagated into every `synquest` result. And citations could not be grounded: an answer could name a document but not a page, because no positional data survived parsing.

---

## §67. Architectural Invariants

Part IX is bound by seven invariants. Implementations MUST satisfy all of them.

1. **The platform owns the document model.** No external parser's schema is canonical (§69).
2. **Processors are selected at runtime, never referenced statically by callers.** `synflux` MUST NOT import adapter types (§71).
3. **A processor failure MUST NOT fail an ingestion job.** The fallback ladder always terminates in a usable result (§73).
4. **Document processing is a CPU workload.** No dependency on the GPU Execution Plane (Part VIII).
5. **Structured extraction is off by default.** With the flag off, behaviour is identical to v1.20 (§75).
6. **Removing an adapter module from the build MUST revert its media type to fallback handling with no code change.** This is the operational test of cloaking (§72.5).
7. **Raw bytes remain the sole durable artifact in this scope.** `CanonicalDocument` is in-memory only; persistence requires a separate proposal (§70.4).

### §67.4 Why no GPU dependency

OpenDataLoader's deterministic mode runs entirely on CPU. Its optional hybrid mode requires a separately installed local AI backend and is out of scope (§72.6). Part IX is therefore independent of GPU-plane readiness, and the two workstreams can proceed in parallel.

---

## §68. `DocumentProcessor` SPI

**Module:** `java/document-processing`
**Package:** `org.synanton.docprocessing.api`

The SPI is the platform's processing contract. It is expressed in Synanton types only; no parser library appears in any signature.

```java
public interface DocumentProcessor {

    /** Stable identifier, e.g. "pdf-opendataloader", "tika-fallback". */
    String id();

    /** Implementation version, recorded in ExtractionInfo for reprocessing decisions. */
    String version();

    /** Media types handled, e.g. ["application/pdf"]. */
    Set<String> supportedMediaTypes();

    /** Selection priority; highest wins. The Tika fallback registers at 0. */
    int priority();

    boolean supports(RawDocument document);

    ProcessingResult process(RawDocument document, ProcessingContext context);
}
```

### §68.1 Inputs

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
```

`ProcessingContext` carries the effective per-document budget rather than letting processors read global configuration, so a caller can tighten a timeout for one document without touching platform config.

### §68.2 Output

```java
public record ProcessingResult(
    Outcome outcome,
    CanonicalDocument canonicalDocument,   // null iff outcome == FAILED
    String plainText,                      // ALWAYS populated (see below)
    Map<String, String> extractedMetadata,
    List<ProcessingDiagnostic> diagnostics,
    ExtractionInfo extractionInfo
) {
    public enum Outcome { SUCCESS, PARTIAL, FAILED }
}

public record ProcessingDiagnostic(
    Severity severity,          // INFO, WARN, ERROR
    String code,                // stable, e.g. "ODL_TIMEOUT", "PAGE_SKIPPED"
    String message,
    Integer pageNumber          // nullable
) {}
```

**`plainText` is a total obligation.** It MUST be populated for `SUCCESS`, `PARTIAL`, *and* `FAILED`. On `FAILED` it may be empty, but it is never null. This single rule is what makes the whole layer backward compatible: the chunk stage reads `plainText` and cannot observe whether structured extraction succeeded, degraded, or failed.

### §68.3 Outcome semantics

| Outcome | Meaning | `canonicalDocument` | Caller action |
|---|---|---|---|
| `SUCCESS` | Structure extracted; no material loss | present | proceed |
| `PARTIAL` | Usable structure, some loss (e.g. 3 of 400 pages skipped) | present | proceed, record diagnostics |
| `FAILED` | No usable structure produced | `null` | descend the fallback ladder (§73) |

`PARTIAL` exists so that a single malformed page in a 400-page book does not discard 397 good ones. A processor MUST prefer `PARTIAL` over `FAILED` whenever it produced a document a consumer can use.

### §68.4 Processor obligations

A conforming processor MUST:

- return within `context.timeout()`, or return `FAILED`/`PARTIAL` with a timeout diagnostic;
- never throw for malformed input — malformed input is a `FAILED` result, not an exception (§73.2 covers processors that violate this);
- populate `ExtractionInfo` fully, including `processorId` and `version`;
- emit blocks in reading order with dense, monotonically increasing ordinals (§69.3);
- leave no temporary files or spawned processes behind, including on the timeout path.

---

## §69. Canonical Document Model

**Module:** `java/document-processing`
**Package:** `org.synanton.docprocessing.model`
**Resolves approval item 2** (preliminary schema, aligned before coding starts).

This is the platform's own representation. Per §67.1, no external parser's JSON schema is canonical — adapters map *into* this model, and a mapping gap is the adapter's problem, not a reason to change the model.

### §69.1 Normative Java outline

```java
public record CanonicalDocument(
    UUID contentRefId,
    DocumentMetadata metadata,
    List<DocumentBlock> blocks,      // flat, in reading order
    ExtractionInfo extraction
) {}

public record DocumentMetadata(
    String title,                    // nullable
    String author,                   // nullable
    String language,                 // BCP 47, nullable
    Integer pageCount                // nullable
) {}

public record DocumentBlock(
    int ordinal,                     // global reading-order position, dense from 0
    BlockType type,
    int level,                       // heading depth 1..6; 0 for non-headings
    String text,                     // normalized; empty for IMAGE
    PageRef page,
    BoundingBox bbox,                // nullable
    TableContent table,              // non-null iff type == TABLE
    Integer parentOrdinal            // enclosing section's ordinal; nullable
) {}

public enum BlockType {
    HEADING, PARAGRAPH, LIST_ITEM, TABLE, CAPTION,
    FOOTNOTE, IMAGE, FORMULA, CODE, HEADER_FOOTER, UNKNOWN
}

public record PageRef(int pageNumber, Integer labelledPage) {}   // 1-based; label e.g. "xii"

public record BoundingBox(float x, float y, float width, float height, String unit) {}

public record TableContent(
    List<String> headers,
    List<List<String>> rows,
    String markdown                  // rendered form, used for embedding
) {}

public record ExtractionInfo(
    String processorId,
    String processorVersion,
    int canonicalSchemaVersion,
    Instant startedAt,
    Instant completedAt,
    boolean structured               // false when produced by a flat fallback
) {}
```

### §69.2 Illustrative JSON

A two-page excerpt, showing a heading, a paragraph beneath it, and a table:

```json
{
  "contentRefId": "8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "metadata": { "title": "Distributed Systems", "author": "M. Kleppmann",
                "language": "en", "pageCount": 412 },
  "blocks": [
    { "ordinal": 0, "type": "HEADING", "level": 1,
      "text": "Chapter 7. Transactions",
      "page": { "pageNumber": 221, "labelledPage": "221" },
      "bbox": { "x": 72.0, "y": 90.0, "width": 340.0, "height": 24.0, "unit": "pt" },
      "table": null, "parentOrdinal": null },

    { "ordinal": 1, "type": "PARAGRAPH", "level": 0,
      "text": "In the harsh reality of data systems, many things can go wrong.",
      "page": { "pageNumber": 221, "labelledPage": "221" },
      "bbox": { "x": 72.0, "y": 124.0, "width": 340.0, "height": 48.0, "unit": "pt" },
      "table": null, "parentOrdinal": 0 },

    { "ordinal": 2, "type": "TABLE", "level": 0,
      "text": "Isolation level | Dirty read | Lost update",
      "page": { "pageNumber": 222, "labelledPage": "222" },
      "bbox": { "x": 72.0, "y": 210.0, "width": 400.0, "height": 96.0, "unit": "pt" },
      "table": {
        "headers": ["Isolation level", "Dirty read", "Lost update"],
        "rows": [["Read committed", "prevented", "possible"],
                 ["Snapshot", "prevented", "prevented"]],
        "markdown": "| Isolation level | Dirty read | Lost update |\n|---|---|---|\n| Read committed | prevented | possible |\n| Snapshot | prevented | prevented |"
      },
      "parentOrdinal": 0 }
  ],
  "extraction": {
    "processorId": "pdf-opendataloader",
    "processorVersion": "2.0.0",
    "canonicalSchemaVersion": 1,
    "startedAt": "2026-08-26T10:15:02.113Z",
    "completedAt": "2026-08-26T10:15:07.884Z",
    "structured": true
  }
}
```

This example belongs in the module `README` per the approval record, so the team can react to a concrete shape before implementation begins.

### §69.3 Design decisions

**Flat block list, not a tree.** Nesting is expressed by `level` and `parentOrdinal` rather than nested children. Reading order is the primary consumer need and a flat list makes it unambiguous; a chunker iterates without tree traversal; serialization stays simple; and section hierarchy remains recoverable by walking `parentOrdinal`. A tree would privilege a structure that many PDFs do not cleanly have.

**Ordinals are dense and global.** `ordinal` runs from 0 across the whole document, not per page. Dense ordinals let `parentOrdinal` be a plain index and make chunk provenance a simple range.

**`canonicalSchemaVersion` is explicit and separate from `processorVersion`.** Reprocessing must distinguish "extracted by an older parser" from "extracted into an older schema"; these evolve independently, so both are recorded.

**`TableContent.markdown` is precomputed.** Embedding a table requires a linear form, and rendering it at extraction time keeps that decision with the component that understands the table's geometry.

**`bbox` is nullable, `page` is not.** Every block belongs to a page; not every processor yields geometry. Making `page` mandatory keeps citations viable even from processors without bounding boxes.

### §69.4 Normalization rules

Processors MUST normalize before emitting blocks, so that consumers need no per-processor special cases:

- Unicode NFC; soft hyphens removed; hyphenation across line breaks rejoined.
- Intra-block line wrapping collapsed to single spaces; paragraph breaks become block boundaries.
- Repeating running heads and folios classified as `HEADER_FOOTER`, not `PARAGRAPH`, so consumers can drop them.
- Ligatures decomposed (`ﬁ` → `fi`).
- `text` for a `TABLE` block is a flattened readable form; the authoritative structure lives in `table`.

---

## §70. Document Model Lifecycle

This section fixes the relationship between `CanonicalDocument` and `synflux`'s `ParsedDocument` so the two cannot drift into competing models.

### §70.1 Roles

The two types are **not** peers, and they are **not** two views of the same thing maintained in parallel:

| | `CanonicalDocument` | `ParsedDocument` |
|---|---|---|
| Role | **Authoritative** extraction output | Pipeline **stage envelope** |
| Owner | `document-processing` | `synflux` |
| Contains | structure, provenance, geometry | the canonical document + a flattened text projection + the acquired bytes |
| Lifetime | created by a processor; released after chunking (§74.3) | one pipeline pass |
| Visible to | any consumer of the SPI | `synflux` stages only |

`ParsedDocument` is a **transport envelope**, not a simplified competing model. It exists because `synflux` stages are typed `PipelineStage<In, Out>` and need a single value to pass along; it carries the canonical document rather than replacing it.

### §70.2 The v1.21 shape

```java
public record ParsedDocument(
    AcquiredDocument acquired,
    String text,                        // flattened projection; retained for compatibility
    Map<String, String> metadata,
    CanonicalDocument canonical         // NEW in v1.21; null when structured extraction
) {}                                    //   is disabled or the fallback produced flat text
```

Exactly one field is added, and it is nullable. `ChunkStage`, `EnrichStage`, `EmbedStage`, and `PersistStage` compile and behave unchanged.

**`text` is a projection, never an independent source.** When `canonical != null`, `text` MUST equal the flattening of `canonical.blocks()` in ordinal order. It is derived, never separately edited. This is the rule that prevents the two representations from disagreeing.

### §70.3 Intended trajectory

The long-term direction is explicit, so that near-term code is written in the right direction:

```text
v1.21 (this version)
  ParseStage → ParsedDocument{ text, canonical? } → ChunkStage reads text
                                                    canonical unused downstream

next (structure-aware chunking, separate proposal)
  ParseStage → ParsedDocument{ text, canonical } → ChunkStage reads canonical
                                                    text becomes vestigial

eventual
  ParseStage → CanonicalDocument → ChunkStage
                                    text projection deleted
```

`ParsedDocument.text` is therefore a **compatibility affordance with a planned end**, not a permanent parallel model. New code MUST NOT add consumers of `text` when `canonical` is available. Two models coexist for one release so the change lands without a pipeline-wide rewrite; they do not coexist by design.

The corollary for §71–§73: consumers that need structure read `canonical` and handle `null` by degrading, never by reconstructing structure from `text`. Re-deriving structure from flattened text would recreate exactly the loss Part IX removes.

### §70.4 Persistence

**`CanonicalDocument` is not persisted in this scope.** Only raw bytes (to object storage) and chunk text (to Cassandra) are durable, exactly as in v1.20. `ExtractionInfo` is likewise in-memory.

This keeps v1.21 reversible and avoids a Cassandra migration while extraction quality is still unproven. It is a deliberate cost: reprocessing a document requires re-extraction rather than a reload. Persisting the canonical document — and with it positional citations and bounding-box storage — requires a schema migration and belongs to a follow-up proposal informed by measured output size.

The `ManifestRow.chunkStrategyVersion` and `schemaVersion` fields already present in `ingestion-cache` are the natural hooks for that future work; v1.21 does not repurpose them.

---

## §71. `DocumentProcessorRegistry`

**Module:** `java/document-processing`

The registry is the cloaking boundary. Callers ask for a processor by document characteristics and receive an SPI reference; they never name an implementation.

```java
public class DocumentProcessorRegistry {

    private final List<DocumentProcessor> processors;   // injected, all on classpath
    private final DocumentProcessor fallback;           // Tika, priority 0

    /** Highest-priority processor whose supports() accepts the document. */
    public DocumentProcessor select(RawDocument document);

    /** Ordered ladder for a document: candidates, then fallback last (§73). */
    public List<DocumentProcessor> selectLadder(RawDocument document);
}
```

### §71.1 Selection

Selection is media type, then priority, then registration order as a stable tiebreak:

1. Filter to processors whose `supportedMediaTypes()` contains the document's media type and whose `supports()` returns `true`.
2. Sort by `priority()` descending.
3. Append the fallback if not already present.

The registry resolves against whatever is on the classpath at runtime. Deploying the ODL module changes PDF routing; removing it reverts PDF routing — in both cases with no code change, satisfying §67.6.

### §71.2 Media-type detection

`AcquiredDocument.mimeType` originates upstream and is not always specific; `application/octet-stream` is the common degenerate case. When the declared media type is generic or absent, the registry MUST run Tika's detector on the leading bytes and route on the detected type.

Tika is already a `synflux` dependency, detection reads only a prefix, and the cost is negligible against extraction. A document whose type cannot be determined routes to the fallback.

### §71.3 Prohibited coupling

`synflux` MUST NOT import from `document-processing-pdf-odl`, and the module MUST NOT appear in `synflux`'s compile classpath — only its runtime classpath. Verifying this is a build-time concern (§77.2): a compile-scope dependency would silently defeat §67.6 while leaving every test green.

---

## §72. OpenDataLoader Adapter

**Module:** `java/document-processing-pdf-odl` (isolated)
**Processor id:** `pdf-opendataloader`
**Media type:** `application/pdf`
**Priority:** 100

### §72.1 Dependency and licence

Pinned per approval item 4:

```kotlin
// gradle/libs.versions.toml
opendataloader = "2.0.0"
opendataloader-pdf-core = { module = "org.opendataloader:opendataloader-pdf-core",
                            version.ref = "opendataloader" }
```

OpenDataLoader PDF `2.0.0` is Apache-2.0 (releases before 2.0 were MPL-2.0, so the pin is also a licence decision), requires Java 11+, and runs fully offline with no GPU. The pinned version MUST be recorded in `THIRD_PARTY` with its licence, and it is surfaced at runtime as `ExtractionInfo.processorVersion`.

The version is pinned exactly, not as a range, so that extraction output is reproducible — a silent parser upgrade would change chunk boundaries and therefore embeddings.

### §72.2 Responsibility

The adapter's entire job is **translation**: invoke OpenDataLoader, map its JSON output into `CanonicalDocument`, and convert its failures into `ProcessingResult` outcomes. It contains no platform business logic, and no OpenDataLoader type escapes the module.

```text
RawDocument ──▶ temp file ──▶ OpenDataLoader ──▶ ODL JSON
                                                     │
                                                     ▼
                                              block mapping (§72.3)
                                                     │
                                                     ▼
                                            CanonicalDocument
```

### §72.3 Block mapping

ODL element kinds map to `BlockType` explicitly. Unrecognized kinds map to `UNKNOWN` and are retained — dropping unknown content would silently lose text.

| ODL element | `BlockType` | Notes |
|---|---|---|
| heading / title | `HEADING` | `level` from ODL depth, clamped to 1..6 |
| paragraph / text | `PARAGRAPH` | |
| list item | `LIST_ITEM` | marker stripped from `text` |
| table | `TABLE` | populates `TableContent`, including `markdown` |
| caption | `CAPTION` | linked to preceding figure/table via `parentOrdinal` |
| footnote | `FOOTNOTE` | |
| image / figure | `IMAGE` | `text` empty; `bbox` retained |
| formula | `FORMULA` | |
| code | `CODE` | |
| running head / folio | `HEADER_FOOTER` | classified, not dropped (§69.4) |
| *anything else* | `UNKNOWN` | text preserved |

Reading order comes from ODL's own ordering; the adapter assigns dense ordinals over it. `parentOrdinal` is computed by tracking the most recent `HEADING` at each level.

### §72.4 Resource hygiene

OpenDataLoader operates on files, while the pipeline holds bytes in memory. The adapter therefore stages a temporary file and MUST guarantee cleanup:

- staging directory from `synflux.parse.temp-dir`, defaulting to the JVM temp dir;
- file name derived from `contentRefId`, never from `filename` (an untrusted, source-controlled value);
- permissions restricted to the service user;
- deletion in a `finally` block, so the timeout and exception paths clean up too;
- no reliance on JVM shutdown hooks for deletion.

A leaked temp file per document would exhaust disk over a long library scan, which is why cleanup is normative rather than advisory.

### §72.5 Removability

Per §67.6, removing this module from the build MUST revert PDF handling to the Tika fallback with no code change. This is verified operationally, not merely asserted: the validation plan (§78.3) drops the module and reruns ingestion.

### §72.6 Hybrid mode excluded

OpenDataLoader's optional hybrid mode improves complex tables, OCR, and formulas, but requires a separately installed Python backend served on a local port. It is out of scope for v1.21: it adds a process dependency and a failure mode that the deterministic path does not have. It is evaluated only if deterministic-mode measurements prove insufficient.

---

## §73. Error Boundaries and Fallback Contract

**Resolves approval item 1**, which requires explicit fallback to Tika on *any* adapter failure — including timeout, malformed output, and ODL exceptions — before a `FAILED` result is returned.

### §73.1 The governing rule

> **A document-processing failure MUST NOT fail an ingestion job.**

An ingestion job over a books library will encounter encrypted PDFs, truncated downloads, and files whose extension lies about their content. Any of these failing the job would make ingestion unusable at library scale. v1.20 already honoured this with a broad `catch` in the parse stage; v1.21 preserves the guarantee and makes it precise.

### §73.2 The fallback ladder

The parse stage descends a ladder and stops at the first rung yielding usable text:

```text
  1. structured processor (e.g. pdf-opendataloader, priority 100)
        │  FAILED, exception, or timeout
        ▼
  2. Tika fallback (flat text, priority 0)
        │  FAILED or exception
        ▼
  3. empty ParsedDocument{ text = "", canonical = null }
        │
        ▼
     job continues; document recorded with diagnostics
```

Rung 3 always succeeds, so the ladder always terminates. A document that reaches rung 3 still flows through the pipeline: bytes are persisted, the manifest is written, and zero chunks are produced. **No exception escapes the parse stage.**

### §73.3 Failures the adapter MUST convert, not propagate

The SPI requires processors not to throw for bad input (§68.4), but the parse stage cannot rely on a third-party library honouring that. Both sides are therefore defensive: the adapter converts what it can anticipate, and the stage catches anything remaining.

| Condition | Adapter behaviour | Result | Falls back? |
|---|---|---|---|
| Encrypted / password-protected | detect, do not attempt | `FAILED` + `ODL_ENCRYPTED` | yes |
| Malformed or truncated PDF | catch ODL exception | `FAILED` + `ODL_MALFORMED` | yes |
| Not actually a PDF | detect via magic bytes | `FAILED` + `ODL_NOT_PDF` | yes |
| Timeout exceeded | cancel, clean up temp files | `FAILED` + `ODL_TIMEOUT` | yes |
| Unmappable ODL JSON | catch mapping error | `FAILED` + `ODL_MAPPING_ERROR` | yes |
| Some pages unreadable | keep good pages | `PARTIAL` + `PAGE_SKIPPED` per page | **no** |
| Zero blocks from a non-empty file | treat as no structure | `FAILED` + `ODL_EMPTY_RESULT` | yes |
| `OutOfMemoryError` | do not catch | propagates | see §73.4 |

`PARTIAL` deliberately does not fall back: a document with 397 of 400 pages extracted is better than Tika's flat rendering of all 400. Falling back there would trade real structure for completeness that chunking cannot exploit.

### §73.4 `Error` is not caught

The parse stage catches `Exception`, not `Throwable`. `OutOfMemoryError` and other `Error`s propagate.

This is deliberate. A heap exhausted by one document is a JVM-wide condition, not a property of that document: catching it and continuing would let a degraded JVM process the rest of the library, producing results no one should trust. Failing loudly is the correct behaviour, and §74 exists to keep the condition from arising.

### §73.5 Timeout enforcement

`ProcessingContext.timeout()` is enforced by the parse stage, not left to processor goodwill: extraction runs on a bounded executor and is cancelled on expiry. A processor that ignores cancellation cannot hang the pipeline indefinitely, and the temp-file cleanup in §72.4 runs on the cancellation path.

The default of 60s per document (§75) is generous for a normal book and short enough that a pathological file cannot stall a job for long.

### §73.6 Diagnostics and observability

Every fallback records the originating processor id, the diagnostic code, and the rung reached. Fallbacks are counted by `document_parse_fallback_total{from,to,reason}` (§76).

Fallback rate is the primary health signal for this layer: a rising rate means extraction quality is silently degrading toward v1.20 behaviour while jobs continue to report success. Without this metric that regression would be invisible — which is precisely why §73.1's "never fail the job" guarantee needs a counter attached to it.

---

## §74. Memory Model and Object Lifetime

**Resolves approval item 3.** The concern raised in review — that a structured document for a 400-page book is far larger in memory than a flat string — is correct but is **not** the dominant term. This section states the actual retention profile, because acting on the intuition alone would optimize the wrong object.

### §74.1 What actually dominates: raw bytes

The v1.20 pipeline already retains every in-flight document's raw bytes for the entire pass. The reference chain is:

```text
ChunkedDocument ──▶ ParsedDocument ──▶ AcquiredDocument ──▶ byte[]
```

`PersistStage` uploads `acquired.bytes()` to object storage as the **last** stage, so the bytes stay strongly reachable across the slow enrich and embed stages — the stages that dominate wall-clock time. With `synflux.ingest.parallelism: 4` and `max-file-size-bytes: 104857600`, the worst case is roughly 4 × 100 MB of raw bytes alone, before any parsed representation.

The canonical document is a fraction of that: block text is bounded by the document's extractable text, plus per-block overhead of an ordinal, an enum, a page ref, and an optional 4-float box.

**Therefore: chunking earlier does not address the dominant term.** Bytes are pinned by `PersistStage`'s position, not by when chunking happens. This retention predates v1.21 and is unchanged by it.

### §74.2 What v1.21 adds

Peak heap per in-flight document under v1.21:

```text
  raw bytes            (unchanged from v1.20, dominant)
+ flattened text       (unchanged from v1.20)
+ CanonicalDocument    (new: block text ≈ flattened text, + per-block overhead)
```

The new term is roughly proportional to extractable text, so it scales with the document's text content rather than its file size. For a text-heavy book the canonical document is comparable to the flat text; for an image-heavy PDF it is far smaller than the bytes.

### §74.3 Object-lifetime rules

Two rules bound the increase. They rest on a verified fact about the current pipeline: **no stage after `ChunkStage` reads `ParsedDocument.text` or `canonical`.** `EnrichStage`, `EmbedStage`, and `PersistStage` read only `parsed().acquired()`, and chunk text is copied into `Chunk` records by `ChunkStage`.

**Rule 1 — project the envelope after chunking.** Once `ChunkStage` has produced its chunks, the pipeline replaces the envelope with one whose `canonical` and `text` are dropped, retaining only `acquired`. Because nothing downstream reads those fields, this is invisible to later stages and confines the canonical document to the parse-plus-chunk window instead of the whole pass.

**Rule 2 — never hold two representations of one document.** A processor MUST NOT retain its parser-native output after mapping to `CanonicalDocument`. The adapter releases ODL's JSON before returning.

### §74.4 Deliberately not addressed

Three larger changes are noted and excluded, so the reasoning is not relitigated during implementation:

**Streaming raw bytes to object storage before parsing** would remove the dominant term outright by letting the bytes be collected early. It touches `AcquireStage`, `PersistStage`, and the manifest write ordering — a restructuring of the v1.20 pipeline that a parsing PoC should not carry.

**Decoupling parse concurrency from `synflux.ingest.parallelism`** would allow, say, 8 concurrent ingests with 2 concurrent extractions. This is the right fix *if* measurement shows extraction is the memory constraint, and §78.2 measures exactly that. Designing it before the measurement would be speculative.

**Spilling `CanonicalDocument` to disk** for very large documents adds I/O and lifecycle complexity that the §74.1 analysis does not justify.

### §74.5 Measurement obligation

Because the analysis above is reasoning rather than observation, it MUST be validated: peak heap is measured at `parallelism: 4` and `parallelism: 8` over the books corpus, with structured extraction on and off (§78.2).

Per the approval record, exceeding container limits is an *informative* result that motivates the concurrency-decoupling follow-up — not a PoC failure. What would be a genuine problem is discovering that the canonical document, and not the raw bytes, dominates; that would invalidate §74.1's reasoning and reopen the earlier-chunking question.

---

## §75. Configuration

**Module:** `synflux`. All keys have defaults preserving v1.20 behaviour.

```yaml
synflux:
  parse:
    structured-extraction-enabled: false   # master switch; false ⇒ v1.20 behaviour
    timeout: 60s                           # per-document budget (§73.5)
    temp-dir: ${java.io.tmpdir}            # ODL staging (§72.4)
    fallback-enabled: true                 # always-on in v1.21 (§75.2)
    max-blocks-per-document: 100000        # guard against pathological output
    processors:
      pdf-opendataloader:
        enabled: true                      # effective only if module is present
```

Extending the existing properties record:

```java
@ConfigurationProperties(prefix = "synflux")
public record SynfluxProperties(
    Ingest ingest, Pipeline pipeline, Enrichment enrichment,
    Embedding embedding, Kafka kafka,
    Parse parse                                        // NEW
) {
    public record Parse(
        boolean structuredExtractionEnabled,
        Duration timeout,
        String tempDir,
        boolean fallbackEnabled,
        int maxBlocksPerDocument,
        Map<String, ProcessorConfig> processors
    ) {
        public record ProcessorConfig(boolean enabled) {}
    }
}
```

### §75.1 Default-off rationale

Shipping disabled makes the upgrade a no-op until deliberately enabled, keeps rollback a config change rather than a redeploy, and lets the comparative validation (§78) run both arms from one build.

### §75.2 `fallback-enabled` is not a real switch in v1.21

The key exists for forward compatibility but MUST remain `true`. Per the approval record, the fallback is always on in this scope; a future production feature may quarantine failing documents instead of falling back, and that behaviour will need this key. Setting it `false` in v1.21 is unsupported.

---

## §76. Metrics

Low-cardinality only. `processor` and `reason` are bounded sets; media type is normalized to a short token.

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `document_parse_total` | counter | `processor`, `media_type`, `outcome` | volume and outcome mix |
| `document_parse_duration_seconds` | histogram | `processor`, `media_type` | latency vs. the Tika baseline |
| `document_parse_fallback_total` | counter | `from`, `to`, `reason` | silent quality regression (§73.6) |
| `document_parse_blocks` | histogram | `processor`, `block_type` | structure yield; validates §74 sizing |

`document_parse_fallback_total` is the metric to alert on. Jobs succeed by design when extraction fails (§73.1), so a rising fallback rate is the only signal that structured extraction has quietly stopped working.

Document titles, filenames, source URIs, and tenant ids MUST NOT appear as labels — unbounded cardinality, and in the case of titles and URIs, content leakage into metrics.

---

## §77. Impact on Existing Modules

### §77.1 Module-by-module

| Module | Change |
|---|---|
| `document-processing` | **New.** SPI, canonical model, registry, Tika processor. |
| `document-processing-pdf-odl` | **New, isolated.** ODL adapter; runtime classpath only. |
| `synflux` | `ParseStage` delegates to the registry; `ParsedDocument` gains `canonical`; envelope projection after chunking (§74.3); new config and metrics. |
| `synvault` | **Unchanged.** Raw-content and provenance semantics untouched. |
| `ingestion-cache` | **Unchanged.** No schema change; `CanonicalDocument` is not persisted (§70.4). |
| `synquest` | **Unchanged** in v1.21; benefits later via structure-aware chunking. |
| `syntology` | **Unchanged.** Extracted metadata is collected but not yet integrated. |
| `relix` | **Unchanged.** Table structure becomes available to it in a follow-up. |
| `gpu-gateway`, `gpu-contract` | **Unchanged.** No GPU dependency (§67.4). |

The `TikaDocumentProcessor` is a relocation, not a rewrite: the v1.20 parse logic moves behind the SPI at priority 0, keeping the fallback path identical to today's behaviour.

### §77.2 Build-level verification

Two structural properties are asserted by the build, because both can break silently while tests pass:

- `synflux` MUST NOT have a compile-scope dependency on `document-processing-pdf-odl` (§71.3).
- The ODL module MUST be absent from `synflux`'s compile classpath and present only at runtime.

A dependency-scope check enforces these. Without it, an accidental `implementation` declaration would defeat §67.6 invisibly.

---

## §78. Validation

### §78.1 Comparative ingestion

The books-library test environment provides the corpus; setup is documented in `gpu-runtime/doc/TEST_ENVIRONMENT_SETUP.md`.

Two arms run over the same corpus:

| Arm | Tenant | Configuration |
|---|---|---|
| Baseline | `books-tika` | `structured-extraction-enabled: false` |
| Structured | `books-structured` | `structured-extraction-enabled: true` |

**Separate tenants are mandatory, not stylistic.** `IngestionJobRunner` skips documents already at a terminal manifest state, so rerunning the second arm under the same tenant would skip every document and silently produce an empty comparison that looks like a successful run.

### §78.2 Measurements

- Reading-order correctness on multi-column pages, sampled and manually scored.
- Heading extraction and nesting against each book's table of contents.
- Table row/column retention where Tika flattens.
- p50 and p95 parse latency per arm.
- **Peak heap at `parallelism` 4 and 8, both arms** (§74.5).
- Fallback rate by reason.
- Distribution of blocks per document and block type mix.

### §78.3 Acceptance criteria

Confirmed by the approval record:

| # | Criterion |
|---|---|
| 1 | Correct reading order on ≥90% of non-scanned PDFs |
| 2 | Correct heading nesting on ≥80% of documents with a TOC |
| 3 | Tables retain row/column structure where Tika flattens them |
| 4 | p95 parse latency within **5×** the Tika baseline |
| 5 | No parse failure escalates to an ingestion job failure |
| 6 | Removing the ODL module reverts PDF handling with no code change |
| 7 | Peak heap at `parallelism: 8` within the existing container limit |

Criteria 4 and 7 are **informative**: failing them motivates the concurrency follow-up (§74.4) without invalidating the architecture. Criteria 5 and 6 are **structural** — failing either means an invariant (§67.3, §67.6) is not actually held, and no amount of extraction quality compensates.

---

## §79. Follow-Up Work (Out of Scope)

Deferred, each requiring its own proposal:

| Work | Blocked on |
|---|---|
| Structure-aware chunking | measured `CanonicalDocument` output |
| Persisting `CanonicalDocument` to Cassandra | schema migration; measured size |
| Positional citations and bounding-box storage | canonical-document persistence |
| EPUB, DOCX, HTML processors | SPI already accommodates them |
| OCR for scanned PDFs | separate processor evaluation |
| ODL hybrid mode | only if deterministic mode proves insufficient (§72.6) |
| Parse concurrency decoupled from ingest parallelism | §78.2 memory measurements |
| Tenant-level processor policy and quarantine | no evidence of need yet |
| `syntology` integration of extracted metadata | proven extraction quality |

Structure-aware chunking is the change that converts this layer into user-visible improvement: v1.21 makes structure *available*, and nothing yet *consumes* it. That sequencing is intentional — extraction quality is measured before chunking is redesigned around it.

---

## Appendix A (v1.21) — Parse Stage Control Flow

```text
AcquiredDocument
      │
      ▼
media-type detection (§71.2)
      │
      ▼
registry.selectLadder()
      │
      ├── structured processor ──── SUCCESS / PARTIAL ──┐
      │         │                                       │
      │         └── FAILED / throw / timeout             │
      │                    │                             │
      ├── Tika fallback ───┴──────── SUCCESS ───────────┤
      │         │                                        │
      │         └── FAILED / throw                       │
      │                    │                             │
      └── empty result ────┴──────────────────────────────┤
                                                          │
                                                          ▼
                              ParsedDocument{ text, canonical? }
                                                          │
                                                          ▼
                                                    ChunkStage
                                                          │
                                                          ▼
                                    project envelope: drop canonical + text (§74.3)
                                                          │
                                                          ▼
                                          EnrichStage → EmbedStage → PersistStage
```

---

## Appendix B (v1.21) — Module Dependency Diagram

```text
synanton/platform
    │
    ├── synflux
    │     ├── compile ──▶ document-processing (api + model)
    │     └── runtime ──▶ document-processing-pdf-odl   ◀── never compile-scope (§71.3)
    │                            │
    │                            └──▶ org.opendataloader:opendataloader-pdf-core:2.0.0
    │
    ├── document-processing
    │     ├── api/    DocumentProcessor, ProcessingResult, Registry
    │     ├── model/  CanonicalDocument, DocumentBlock, ExtractionInfo
    │     └── tika/   TikaDocumentProcessor (fallback, priority 0)
    │
    └── (unchanged) synvault, ingestion-cache, synquest, syntology, gpu-gateway
```

---

*End of Synanton Platform Architecture v1.21*
