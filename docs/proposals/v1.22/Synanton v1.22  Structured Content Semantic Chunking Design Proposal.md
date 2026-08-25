# Synanton Structured Content Extraction Plane - Multimodal Extraction and Semantic Chunking Design

**Document ID:** SNTP-8-DESIGN-SCEP-1.22
**Date:** 2026-08-25
**Status:** PROPOSED — ARCHITECTURAL DESIGN IN PROGRESS
**Basis:** Synanton v1.20 Architecture, v1.21 Structured Content Extraction Plane proposal, and OpenDataLoader PoC findings
**Purpose:** Define a concrete multimodal extraction model with integrated semantic  chunking, while preserving the deployment-neutral extraction contract.

------

## 1. Executive Summary

The Structured Content Extraction Plane is the boundary between **raw content** and **structured content that can be consumed by Synanton knowledge processing**. Synanton v1.21 established the extraction contract, successfully  shifting the platform from ingesting raw text streams to preserving  observable document structure (headings, paragraphs, tables, images,  formulas, and bounding boxes).

However, extraction alone does not solve the downstream consumption problem.  Traditional RAG and search pipelines often apply naive, fixed-size token chunking to the `flattenedText`  representation. This destroys semantic boundaries, arbitrarily splits  tables, and severs the connection between a piece of text and its  hierarchical context.

**Synanton v1.22** introduces a new conceptual layer: **Semantic Content Structuring / Chunking** that operates on the normalized structured representation (not `flattenedText`) to produce task-optimized, semantically coherent chunks that carry full provenance (`sectionPath`, `sourceElements`, page/bbox).

The extraction plane is deliberately a black box from the Synanton platform perspective. Synanton defines the request, lifecycle, metadata, tags,  capabilities, structured result, and error contract; the extraction  plane decides how the work is performed.

The plane covers multiple content domains:

text

```
                         Raw Content
                              |
                              v
                 +-------------------------+
                 | Structured Content       |
                 | Extraction Plane         |
                 |                         |
                 |  PDF / Text / Ebook     |
                 |  HTML                   |
                 |  Audio / Conversation   |
                 |  Image                  |
                 |  Video / Short Clips    |
                 +------------+------------+
                              |
                              v
                    StructuredPayload
                              |
                              v
              +---------------+----------------+
              |               |                |
              v               v                v
         flattenedText   structure       modality data
                              |
                              v
              +---------------+----------------+
              |               |                |
              v               v                v
        Semantic      Summarization      Search Metadata
        Chunks        Context            (citation & filtering)
```



The extraction plane answers:

> **What is present in this artifact, and what structure can be reliably extracted from it?**

The semantic chunking layer answers:

> **How should this structure be represented for a particular downstream task?**

Knowledge processing answers:

> **What does the extracted content mean in the Synanton domain?**

------

## 2. Architectural Principles

### 2.1 Contract over topology

The extraction contract MUST remain stable whether extraction is:

- embedded in Synanton;
- co-located with Synanton;
- deployed as a separate service;
- deployed as a horizontally scaled extraction cluster;
- delegated to specialized processors;
- routed through CPU/GPU workers;
- routed to another extraction provider.

text

```
Synanton
    |
    | Structured Extraction Contract
    v
Structured Content Extraction Plane
    |
    +--> PDF processor
    +--> HTML parser
    +--> OCR
    +--> speech transcription
    +--> speaker diarization
    +--> image/VLM analysis
    +--> video scene/clip processor
    +--> specialized external extractor
```



The internal topology is not part of the Synanton contract.

### 2.2 Source remains authoritative

The raw source artifact is immutable from the extraction plane's perspective.

Every extraction result SHOULD retain:

- `contentRefId`;
- source object reference;
- source checksum;
- media type;
- extraction timestamp;
- schema/version;
- processor information;
- payload digest.

### 2.3 Structure before meaning

Extraction SHOULD preserve observable structure before attempting interpretation.

For example, a PDF should preserve:

text

```
document
  page
    heading
    paragraph
    table
    image
    caption
    formula
```



rather than immediately turning the entire PDF into a single text string.

Likewise, a conversation should preserve:

text

```
conversation
  utterance
    speaker
    start
    end
    text
    overlap
    pause-before
    pause-after
```



rather than returning only a transcript.

### 2.4 Structure before chunking

The chunking layer operates on the **structured representation**, not on `flattenedText`. The chunker should have access to:

- Element types (heading, paragraph, table, list, image, caption, formula)
- Heading hierarchy and levels
- Reading order
- Page and bounding-box information
- Element relationships (e.g., which paragraph belongs to which section)

### 2.5 LLM/VLM enrichment is an extraction stage, not the knowledge layer

LLMs and VLMs MAY be used when deterministic extraction is insufficient.

Examples:

- describe a chart;
- describe an image;
- summarize a conversation;
- identify a video scene;
- interpret visual content.

The generated result MUST be represented as an explicitly typed extraction  artifact with provenance and confidence where appropriate.

The generated interpretation MUST NOT silently become canonical business knowledge.

------

## 3. Core Principles of Semantic Chunking

### 3.1 The Problem with Traditional Chunking

Consider a PDF containing:

text

```
3. GPU Execution Plane

The GPU execution plane is physically separated
from the CPU control plane.

3.1 GPU Gateway

The gateway provides...

Table 4: Execution classes

| Class       | Timeout | Priority |
|-------------|---------|----------|
| Interactive | 60s     | High     |
| Batch       | 30m     | Normal   |

3.2 Scheduling

The scheduler...
```



A conventional 500-token chunker might produce:

text

```
chunk 17:
"The GPU execution plane ... 3.1 GPU Gateway ...
The gateway provides ... Table 4 ... Interactive ...
Batch ..."

chunk 18:
"30m Normal ... 3.2 Scheduling ..."
```



The semantic boundaries are lost. Retrieval becomes imprecise, citations  become unreliable, and downstream LLM processing lacks the context  needed for accurate reasoning.

A **structure-aware chunker** instead produces chunks that respect document semantics.

### 3.2 Core Principles

1. **Structured Input Only:** The chunker MUST operate on the normalized `elements` collection. `flattenedText` MUST NOT be the primary input for chunk boundary decisions.

2. **Semantic Boundaries First:** Chunk boundaries SHOULD follow document semantics (section hierarchy, lists, tables, figures).

3. **Hierarchical, Not One-Heading-Per-Chunk:** A heading defines a semantic boundary, but not necessarily the final embedding chunk. For example:

   text

   ```
   2. Architecture
       2.1 Control Plane
           8 paragraphs
           2 tables
           3,500 tokens
   ```

   

   That section is semantically coherent but too large for one embedding. The chunker must be hierarchical and constraint-based.

4. **First-Class Tables:** Tables MUST NOT be split arbitrarily. They are treated as atomic,  first-class chunk types with structured row/column representation.

5. **Hierarchical Context (`sectionPath`):** Every chunk MUST carry its heading hierarchy (e.g., `["3. GPU Execution Plane", "3.1 GPU Gateway"]`) to provide context during retrieval.

6. **Constraint-Based Fallback:** If a semantic section exceeds the target token/character budget, split  at paragraph or list boundaries. Pure token-based splitting is strictly a final fallback.

7. **Provenance Preservation:** Every chunk MUST retain traceability to its source elements (e.g., `sourceElements: ["p7-e12", "p7-e13"]`, `pageStart`, `pageEnd`).

------

## 4. Content Domains

The initial extraction plane should support five major domains.

| Domain    | Typical inputs                    | Primary extraction                        | Optional enrichment                                       |
| --------- | --------------------------------- | ----------------------------------------- | --------------------------------------------------------- |
| Documents | PDF, TXT, EPUB, HTML              | text, structure, layout, metadata         | OCR, tables, formulas, image descriptions, summaries      |
| Audio     | WAV, MP3, M4A, meeting recordings | transcription, timestamps                 | diarization, pauses, overlap, conversation summary        |
| Images    | PNG, JPEG, TIFF, screenshots      | image metadata, OCR                       | object/scene description, chart interpretation            |
| Video     | MP4, WebM, MOV                    | metadata, audio/video streams, key frames | transcription, OCR, scene detection, short-clip summaries |

The common result model should not force every modality into a document abstraction.

Instead:

text

```
StructuredPayload
    |
    +-- DocumentPayload
    +-- ConversationPayload
    +-- ImagePayload
    +-- VideoPayload
```



A modality can expose a `flattenedText` compatibility projection when meaningful.

------

## 5. Common Extraction Contract

The existing v1.21 contract remains the architectural boundary.

Illustrative request:

java

```
public record ExtractionRequest(
    UUID contentRefId,
    ObjectReference source,
    String mediaType,
    ExtractionOptions options,
    Map<String, String> tags,
    ExtractionPriority priority,
    Instant expiresAt,
    String idempotencyKey
) {}
```



The request separates:

### Source

text

```
contentRefId
object reference
media type
size
checksum
```



### Extraction options

Capabilities requested by the caller:

text

```
ocr
layout
tables
transcription
diarization
pause-detection
overlap-detection
image-description
scene-detection
clip-summary
```



### Tags

Tags carry extraction intent and/or business metadata.

They SHOULD be divided conceptually into:

text

```
capability tags
business tags
provenance tags
```



------

## 6. Tag Model

Tags are important because multimodal extraction is not one fixed operation.

### 6.1 Capability tags

Capability tags request additional extraction features.

Examples:

text

```
extract=text
extract=layout
extract=tables
extract=images
extract=ocr
extract=formulas

audio=transcription
audio=diarization
audio=pauses
audio=overlap
audio=summary

image=ocr
image=description
image=objects
image=chart-analysis

video=keyframes
video=transcription
video=ocr
video=scenes
video=clip-summary

# Chunking-specific tags
chunking=structure-aware
chunking=max-tokens=512
chunking=table-atomic
```



These tags express **what the caller wants**, not which processor must be used.

### 6.2 Business tags

Business tags travel with the request and result but remain opaque to the extraction plane.

Examples:

text

```
tenant=acme
department=legal
case-id=CASE-1842
source-system=sharepoint
document-class=contract
```



The extraction plane SHOULD preserve these values.

It SHOULD NOT infer business semantics from them.

### 6.3 Result feature tags

The result SHOULD explicitly report what was actually produced.

Example:

text

```
feature.text=applied
feature.layout=applied
feature.tables=applied
feature.ocr=not-applicable
feature.images=applied
feature.image-description=applied
feature.chunking=applied
```



This is preferable to assuming that a requested feature was successfully executed.

### 6.4 Provenance tags

Provenance tags identify how a particular result was created.

Examples:

text

```
processor=opendataloader-pdf
processor-version=2.5.0
mode=deterministic
schema=synanton.document.v1
schema-version=1
```



For generated content:

text

```
generated=true
generator-type=vlm
```



For chunks:

text

```
chunking-strategy=structure-aware-v1
chunker-version=1.0
```



------

## 7. Feature State

Every requested capability SHOULD have an explicit state:

text

```
requested
applied
not-requested
not-applicable
unsupported
failed
partial
```



Example:

json

```
{
  "featureStates": {
    "text": "applied",
    "layout": "applied",
    "tables": "applied",
    "ocr": "not-applicable",
    "image-description": "applied",
    "chunking": "applied"
  }
}
```



This avoids a dangerous ambiguity:

text

```
ocr=true
```



does not tell the caller whether OCR was:

- requested;
- executed;
- unnecessary;
- unsupported;
- failed.

------

## 8. Architecture Overview

### 8.1 Ingestion Pipeline Flow (v1.22)

text

```
Raw Content (PDF, HTML, etc.)
      │
      ▼
┌─────────────────────────────────────────────────┐
│ Structured Content Extraction Plane (v1.21)     │
│                                                  │
│  PDF / HTML / Audio / Image / Video             │
│         ↓                                       │
│  Normalized Document Structure                  │
│    ├── metadata                                 │
│    └── elements[]                               │
│         ├── heading (level, text)               │
│         ├── paragraph (text)                    │
│         ├── list (items)                        │
│         ├── table (headers, rows)               │
│         ├── image (caption, description)        │
│         ├── formula (latex)                     │
│         └── caption                             │
└─────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────┐
│ Semantic Chunking / Structuring Layer (v1.22)   │
│                                                  │
│  Structure Builder                               │
│    └── Document → Section Tree                   │
│                                                  │
│  Semantic Chunker                                │
│    ├── section chunks                            │
│    ├── subsection chunks                         │
│    ├── table chunks                              │
│    ├── figure/image chunks                       │
│    └── fallback token/size splitting             │
│                                                  │
│  Chunk Enricher                                  │
│    ├── sectionPath                               │
│    ├── sourceElements                            │
│    ├── pageStart / pageEnd                       │
│    └── metadata                                  │
└─────────────────────────────────────────────────┘
      │
      ├───────────────────────┬───────────────────────┐
      ▼                       ▼                       ▼
 Embedding Chunks      Summarization Context    Search Metadata
 (optimized for        (optimized for           (optimized for
  retrieval granularity) hierarchical context)   citation & filtering)
```



### 8.2 Separation of Concerns

| Layer                        | Responsibility                                               |
| ---------------------------- | ------------------------------------------------------------ |
| **Extraction Plane (v1.21)** | "What is in this document and what is its structure?"        |
| **Chunking Layer (v1.22)**   | "How should this structure be represented for a particular downstream task?" |
| **Knowledge Processing**     | "What does this mean in the Synanton domain?"                |

*Architectural Invariant:* The chunking logic MUST NOT reside inside the extraction plane itself.  This separation ensures the same extracted document can be chunked  differently for vector search, RAG, summarization, or entity extraction  without re-running extraction.

------

## 9. Detailed Design: Document Extraction

### 9.1 Supported document families

The document domain initially covers:

text

```
PDF
plain text
EPUB / ebook
HTML
```



Additional document formats can be added without changing the extraction contract.

### 9.2 Common document result

A document result SHOULD expose:

json

```
{
  "type": "document",
  "mediaType": "application/pdf",
  "metadata": {},
  "elements": [],
  "flattenedText": "...",
  "featureStates": {}
}
```



The `elements` collection preserves structural information.

Illustrative element:

json

```
{
  "id": "p1-e07",
  "type": "paragraph",
  "location": {
    "page": 1,
    "bbox": [72, 620, 540, 690]
  },
  "text": "The system processes enterprise content..."
}
```



### 9.3 Document Structure Tree

The Structure Builder converts the flat `elements[]` array into a hierarchical tree:

typescript

```
interface DocumentTree {
  documentId: string;
  metadata: DocumentMetadata;
  root: SectionNode;
}

interface SectionNode {
  id: string;
  type: "section" | "subsection" | "paragraph" | "list" | "table" | "figure";
  heading?: string;
  headingLevel?: number;
  sectionPath: string[];  // e.g., ["Architecture", "GPU Execution Plane"]
  content: string;
  children: SectionNode[];
  sourceElements: string[];  // element IDs from extraction
  pageStart: number;
  pageEnd: number;
}
```



------

## 10. Detailed Design: Semantic Chunker

### 10.1 Chunker Configuration

typescript

```
interface ChunkerConfig {
  // Primary constraints
  maxTokensPerChunk: number;      // e.g., 512
  maxCharactersPerChunk: number;  // e.g., 2000
  
  // Semantic preferences
  preferHeadingBoundary: boolean;  // default: true
  keepTableAtomic: boolean;        // default: true
  keepListAtomic: boolean;         // default: true
  keepFigureWithCaption: boolean;  // default: true
  
  // Fallback behavior
  fallbackToTokenSplit: boolean;   // default: true
  minChunkTokens: number;          // default: 50
  
  // Hierarchy preservation
  includeSectionPath: boolean;     // default: true
  includeHeadingInContent: boolean; // default: true
}
```



### 10.2 Chunking Algorithm

text

```
function chunkDocument(documentTree: DocumentTree, config: ChunkerConfig):
  Chunk[] {
  
  chunks = []
  
  for each section in documentTree.root.children:
    // 1. Try semantic boundaries first
    sectionChunks = chunkBySemanticBoundaries(section, config)
    
    for each candidate in sectionChunks:
      // 2. Check size constraints
      if candidate.tokenCount <= config.maxTokensPerChunk:
        chunks.push(candidate)
      else:
        // 3. Split oversized at paragraph/list boundaries
        subChunks = splitAtStructuralBoundaries(candidate, config)
        chunks.push(subChunks)
    
  // 4. Final fallback: token-based splitting
  for each chunk in chunks:
    if chunk.tokenCount > config.maxTokensPerChunk:
      chunks.replace(chunk, splitByTokens(chunk, config))
  
  return chunks
}
```



### 10.3 Chunk Output Model

typescript

```
interface Chunk {
  chunkId: string;              // e.g., "doc1-p12-table3"
  documentId: string;
  
  type: "section" | "subsection" | "paragraph" | "list" | "table" | "figure" | "fallback";
  
  content: string;              // Text representation for embedding
  
  // Structured content (for tables, lists, etc.)
  structuredContent?: {
    table?: TableContent;
    list?: ListContent;
    figure?: FigureContent;
  };
  
  // Hierarchy
  sectionPath: string[];        // e.g., ["Architecture", "GPU Execution Plane", "Scheduling"]
  heading?: string;             // Immediate heading
  
  // Provenance
  sourceElements: string[];     // Element IDs from extraction
  pageStart: number;
  pageEnd: number;
  
  // Metrics
  tokenCount: number;
  
  // Metadata
  metadata: Record<string, any>;
}
```



### 10.4 Table Chunking

Tables receive special treatment as first-class chunk types. The chunker  preserves table structure rather than flattening it into arbitrary text  order:

typescript

```
interface TableChunk extends Chunk {
  type: "table";
  structuredContent: {
    table: {
      caption?: string;
      headers: string[];
      rows: string[][];
    }
  };
  // Embedding representation is generated from structured content
  embeddingRepresentation: string;
}
```



For a table like:

text

```
Table 4: Execution classes
| Class       | Timeout | Priority |
|-------------|---------|----------|
| Interactive | 60s     | High     |
| Batch       | 30m     | Normal   |
```



The embedding representation becomes:

text

```
GPU Execution Plane > Scheduling

Table: Execution classes

Class: Interactive
Timeout: 60s
Priority: High

Class: Batch
Timeout: 30m
Priority: Normal
```



This is significantly better than embedding whatever textual order the PDF parser happens to produce.

------

## 11. Data Model & Examples

### Example 1: Hierarchical Section Chunking

Instead of blindly cutting off mid-sentence, the chunker respects the heading hierarchy and groups related paragraphs.

json

```
{
  "chunkId": "doc-001-c17",
  "documentId": "doc-001",
  "type": "section",
  "heading": "3.1 GPU Gateway",
  "sectionPath": [
    "3. GPU Execution Plane",
    "3.1 GPU Gateway"
  ],
  "content": "The gateway provides a secure, mTLS-authenticated boundary between the primary platform and the GPU cluster. It handles admission, dispatch, and idempotency.",
  "pageStart": 7,
  "pageEnd": 8,
  "sourceElements": ["p7-e12", "p7-e13", "p8-e02"],
  "tokenCount": 142
}
```



*Embedding Representation:* `"3. GPU Execution Plane > 3.1 GPU Gateway: The gateway provides a secure, mTLS-authenticated boundary..."`

### Example 2: First-Class Table Chunk

Traditional chunkers destroy table structure, merging headers and rows into  unreadable text. v1.22 treats tables as atomic units.

json

```
{
  "chunkId": "doc-001-p12-table3",
  "documentId": "doc-001",
  "type": "table",
  "caption": "Table 4: Execution classes",
  "sectionPath": [
    "3. GPU Execution Plane",
    "3.2 Scheduling"
  ],
  "content": "GPU Execution Plane > Scheduling\n\nTable: Execution classes\n\nClass: Interactive\nTimeout: 60s\nPriority: High\n\nClass: Batch\nTimeout: 30m\nPriority: Normal",
  "structuredContent": {
    "table": {
      "caption": "Table 4: Execution classes",
      "headers": ["Class", "Timeout", "Priority"],
      "rows": [
        ["Interactive", "60s", "High"],
        ["Batch", "30m", "Normal"]
      ]
    }
  },
  "pageStart": 12,
  "pageEnd": 12,
  "sourceElements": ["p12-e05"],
  "tokenCount": 85
}
```



### Example 3: Complete Document Structure

**Input Document (Excerpt)**

text

```
3. GPU Execution Plane

The GPU execution plane is physically separated from the CPU control plane.

3.1 GPU Gateway

The gateway provides a secure boundary between the CPU control plane and GPU
execution resources. It handles authentication, authorization, and request
routing to available GPU workers.

Table 4: Execution classes

| Class       | Timeout | Priority |
|-------------|---------|----------|
| Interactive | 60s     | High     |
| Batch       | 30m     | Normal   |

3.2 Scheduling

The scheduler implements a priority-based queue. Interactive jobs preempt
batch jobs when capacity is constrained.
```



**Chunk A — Section Introduction**

json

```
{
  "chunkId": "doc-001-c01",
  "documentId": "doc-001",
  "type": "section",
  "content": "3. GPU Execution Plane\n\nThe GPU execution plane is physically separated from the CPU control plane.",
  "sectionPath": ["3. GPU Execution Plane"],
  "heading": "3. GPU Execution Plane",
  "sourceElements": ["e01", "e02"],
  "pageStart": 7,
  "pageEnd": 7,
  "tokenCount": 28
}
```



**Chunk B — Subsection**

json

```
{
  "chunkId": "doc-001-c02",
  "documentId": "doc-001",
  "type": "subsection",
  "content": "3.1 GPU Gateway\n\nThe gateway provides a secure boundary between the CPU control plane and GPU execution resources. It handles authentication, authorization, and request routing to available GPU workers.",
  "sectionPath": ["3. GPU Execution Plane", "3.1 GPU Gateway"],
  "heading": "3.1 GPU Gateway",
  "sourceElements": ["e03", "e04"],
  "pageStart": 7,
  "pageEnd": 7,
  "tokenCount": 45
}
```



**Chunk C — Table**

json

```
{
  "chunkId": "doc-001-c03",
  "documentId": "doc-001",
  "type": "table",
  "content": "GPU Execution Plane > Scheduling\n\nTable: Execution classes\n\nClass: Interactive\nTimeout: 60s\nPriority: High\n\nClass: Batch\nTimeout: 30m\nPriority: Normal",
  "structuredContent": {
    "table": {
      "caption": "Table 4: Execution classes",
      "headers": ["Class", "Timeout", "Priority"],
      "rows": [
        ["Interactive", "60s", "High"],
        ["Batch", "30m", "Normal"]
      ]
    }
  },
  "sectionPath": ["3. GPU Execution Plane"],
  "heading": "GPU Execution Plane",
  "sourceElements": ["e05"],
  "pageStart": 7,
  "pageEnd": 7,
  "tokenCount": 42
}
```



**Chunk D — Subsection**

json

```
{
  "chunkId": "doc-001-c04",
  "documentId": "doc-001",
  "type": "subsection",
  "content": "3.2 Scheduling\n\nThe scheduler implements a priority-based queue. Interactive jobs preempt batch jobs when capacity is constrained.",
  "sectionPath": ["3. GPU Execution Plane", "3.2 Scheduling"],
  "heading": "3.2 Scheduling",
  "sourceElements": ["e06", "e07"],
  "pageStart": 8,
  "pageEnd": 8,
  "tokenCount": 32
}
```



### Example 4: Handling Oversized Sections (Fallback)

If a section is semantically coherent but too large (e.g., 3,500 tokens),  the chunker splits it at paragraph/list boundaries, not mid-paragraph.

json

```
{
  "chunkId": "doc-001-c22a",
  "documentId": "doc-001",
  "type": "subsection_chunk",
  "heading": "2.1 Control Plane (Part 1)",
  "sectionPath": ["2. Architecture", "2.1 Control Plane"],
  "content": "[First 8 paragraphs and 1 table, totaling ~450 tokens]",
  "pageStart": 4,
  "pageEnd": 5,
  "sourceElements": ["p4-e01", "p4-e02", "p4-e03", "p5-e01"],
  "tokenCount": 450,
  "isPartialSection": true
}
```



------

## 12. Embedding and Summarization: Different Uses of the Same Structure

Version 1.22 distinguishes between embedding and summarization—they use the same structured content differently.

### Embeddings — Optimize for Retrieval Granularity

text

```
document
  ↓
section
  ↓
subsection
  ↓
semantic chunks
  ↓
embedding
```



A subsection might produce several embedding chunks if it is large. Each  chunk is optimized for precise retrieval of specific information.

### Summarization — Optimize for Hierarchical Context

text

```
paragraphs
   ↓
section summary
   ↓
chapter summary
   ↓
document summary
```



This enables:

text

```
Document
│
├── Section 1
│     ├── chunks → embeddings
│     └── summary
│
├── Section 2
│     ├── chunks → embeddings
│     └── summary
│
└── Section 3
      ├── chunks → embeddings
      └── summary
          ↓
     document summary
```



This is much more powerful than sending the entire flattened PDF to an LLM  and asking it to summarize. It also aligns with the v1.21 principle that extraction evidence remains authoritative, while summarization is a  downstream enrichment stage.

------

## 13. Downstream Task Optimization

The v1.22 design explicitly recognizes that different downstream tasks require the structured data in different ways:

| Downstream Task             | Optimization Goal     | v1.22 Strategy                                               |
| --------------------------- | --------------------- | ------------------------------------------------------------ |
| **Vector Embedding / RAG**  | Retrieval granularity | Split large subsections into bounded semantic chunks. Embed the `sectionPath` + `content` to boost contextual relevance. |
| **Summarization**           | Hierarchical context  | Aggregate paragraphs → section summary → chapter summary → document summary. Do not feed raw `flattenedText` to the LLM. |
| **Citation / UI Rendering** | Source reconstruction | Use `sourceElements`, `pageStart`, and `pageEnd` to highlight the exact bounding box in the original PDF viewer. |
| **Metadata Filtering**      | Precise scoping       | Allow users to filter vector search by `sectionPath` (e.g., "Only search within '3. GPU Execution Plane'"). |

------

## 14. PDF Processing PoC — OpenDataLoader PDF

For the PDF PoC, use:

urlopendataloader-project/opendataloader-pdfhttps://github.com/opendataloader-project/opendataloader-pdf

OpenDataLoader PDF currently provides structured JSON, Markdown and HTML output; its  JSON representation includes semantic element types and bounding boxes.  The documented capabilities include reading-order extraction, headings,  lists, tables, images, OCR for scanned PDFs in hybrid mode, formulas,  chart/image descriptions in hybrid mode, Tagged PDF structure  extraction, and content-safety filtering.  citeturn0search0turn0search3

The PoC should therefore use OpenDataLoader as an **implementation behind the Structured Content Extraction Plane**, not as the Synanton extraction contract.

### 14.1 PoC invocation

Python example:

python

```
import opendataloader_pdf

opendataloader_pdf.convert(
    input_path=["sample.pdf"],
    output_dir="output/",
    format="json,markdown"
)
```



The project documents Python, Node.js and Java usage. Its Java examples  generate JSON, Markdown and annotated PDF output.  citeturn0search0turn0search1

For a PoC with a complex/scanned PDF, hybrid mode can be enabled. OCR is supported in hybrid mode and the project documents `--force-ocr` and language selection. citeturn0search0

------

## 15. Example Source PDF

Assume the PoC input is:

text

```
library/
└── enterprise-architecture/
    └── extraction-plane-overview.pdf
```



The PDF contains:

text

```
Page 1
  Title
  Introduction paragraph
  Two-column body text

Page 2
  Heading
  Table
  Caption
  Diagram

Page 3
  Scanned appendix
  Signature image

Page 4
  Mathematical formula
  Chart
```



The extraction request:

json

```
{
  "contentRefId": "01J-PDF-0001",
  "source": {
    "bucket": "content",
    "key": "library/enterprise-architecture/extraction-plane-overview.pdf",
    "version": "v7",
    "sha256": "abc123...",
    "size": 1842031
  },
  "mediaType": "application/pdf",
  "tags": {
    "source-system": "local-library",
    "document-class": "architecture",
    "extract": "text,layout,tables,images,formulas",
    "ocr": "include",
    "image-description": "include",
    "chunking": "structure-aware",
    "chunking-max-tokens": "512"
  },
  "priority": "NORMAL",
  "expiresAt": "2026-08-24T18:00:00Z",
  "idempotencyKey": "pdf-01J-PDF-0001-v7"
}
```



------

## 16. OpenDataLoader Raw JSON Example

The exact OpenDataLoader schema is implementation-specific and MUST NOT be copied directly into the Synanton public contract.

A representative PoC output is:

json

```
{
  "file name": "extraction-plane-overview.pdf",
  "number of pages": 4,
  "author": "Synanton",
  "title": "Structured Content Extraction Plane",
  "creation date": "2026-08-20T10:30:00",
  "modification date": "2026-08-23T14:10:00",
  "kids": [
    {
      "type": "heading",
      "id": 1,
      "page number": 1,
      "bounding box": [72.0, 700.0, 540.0, 730.0],
      "heading level": 1,
      "content": "Structured Content Extraction Plane"
    },
    {
      "type": "paragraph",
      "id": 2,
      "page number": 1,
      "bounding box": [72.0, 640.0, 540.0, 690.0],
      "content": "The extraction plane converts raw enterprise content into structured representations."
    },
    {
      "type": "table",
      "id": 18,
      "page number": 2,
      "bounding box": [72.0, 390.0, 540.0, 580.0],
      "content": {
        "headers": ["Feature", "Purpose"],
        "rows": [
          ["OCR", "Extract text from scanned pages"],
          ["Layout", "Preserve document reading order"],
          ["Tables", "Preserve tabular structure"]
        ]
      }
    },
    {
      "type": "picture",
      "id": 23,
      "page number": 2,
      "bounding box": [72.0, 120.0, 540.0, 350.0],
      "description": "Architecture diagram showing raw content entering an extraction plane and producing structured payloads."
    },
    {
      "type": "formula",
      "id": 31,
      "page number": 4,
      "bounding box": [226.2, 144.7, 377.1, 168.7],
      "content": "\\frac{f(x+h)-f(x)}{h}"
    }
  ]
}
```



**Important:** the values above are a **representative PoC example**, not a claim that these exact elements occur in a particular sample PDF. The OpenDataLoader project documents the element types, fields, and  bounding-box model; actual output depends on the input PDF.  citeturn0search0turn0search3

------

## 17. Synanton Normalized PDF Result

Synanton should normalize processor-specific output into its own modality contract.

json

```
{
  "schema": {
    "id": "synanton.document",
    "version": "1.0"
  },

  "source": {
    "contentRefId": "01J-PDF-0001",
    "mediaType": "application/pdf",
    "sha256": "abc123..."
  },

  "features": {
    "text": "applied",
    "layout": "applied",
    "tables": "applied",
    "images": "applied",
    "ocr": "partial",
    "formulas": "applied",
    "image-description": "applied",
    "chunking": "applied"
  },

  "metadata": {
    "title": "Structured Content Extraction Plane",
    "author": "Synanton",
    "pageCount": 4
  },

  "elements": [
    {
      "id": "p1-e01",
      "type": "heading",
      "page": 1,
      "bbox": [72.0, 700.0, 540.0, 730.0],
      "level": 1,
      "content": "Structured Content Extraction Plane"
    },
    {
      "id": "p1-e02",
      "type": "paragraph",
      "page": 1,
      "bbox": [72.0, 640.0, 540.0, 690.0],
      "content": "The extraction plane converts raw enterprise content into structured representations."
    },
    {
      "id": "p2-e03",
      "type": "table",
      "page": 2,
      "bbox": [72.0, 390.0, 540.0, 580.0],
      "content": {
        "columns": ["Feature", "Purpose"],
        "rows": [
          ["OCR", "Extract text from scanned pages"],
          ["Layout", "Preserve document reading order"],
          ["Tables", "Preserve tabular structure"]
        ]
      }
    }
  ],

  "chunks": [
    {
      "chunkId": "doc-001-c01",
      "type": "heading",
      "content": "Structured Content Extraction Plane",
      "sectionPath": [],
      "heading": "Structured Content Extraction Plane",
      "sourceElements": ["p1-e01"],
      "pageStart": 1,
      "pageEnd": 1,
      "tokenCount": 7
    },
    {
      "chunkId": "doc-001-c02",
      "type": "paragraph",
      "content": "The extraction plane converts raw enterprise content into structured representations.",
      "sectionPath": [],
      "sourceElements": ["p1-e02"],
      "pageStart": 1,
      "pageEnd": 1,
      "tokenCount": 14
    },
    {
      "chunkId": "doc-001-c03",
      "type": "table",
      "content": "Feature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure",
      "structuredContent": {
        "table": {
          "headers": ["Feature", "Purpose"],
          "rows": [
            ["OCR", "Extract text from scanned pages"],
            ["Layout", "Preserve document reading order"],
            ["Tables", "Preserve tabular structure"]
          ]
        }
      },
      "sectionPath": [],
      "sourceElements": ["p2-e03"],
      "pageStart": 2,
      "pageEnd": 2,
      "tokenCount": 28
    }
  ],

  "flattenedText": "Structured Content Extraction Plane\nThe extraction plane converts raw enterprise content into structured representations.\nFeature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure"
}
```



The important architectural distinction is:

text

```
OpenDataLoader JSON
       |
       | adapter / normalizer
       v
Synanton StructuredPayload
       |
       | semantic chunker
       v
Synanton Chunks
```



This prevents OpenDataLoader's field naming or schema evolution from becoming a Synanton platform dependency.

------

## 18. Audio Conversation Extraction

Audio is fundamentally temporal.

A plain transcript is insufficient because it loses conversational structure.

The extraction result SHOULD preserve:

text

```
speaker
start time
end time
text
pause before
pause after
overlap
confidence
```



Example:

json

```
{
  "type": "conversation",
  "mediaType": "audio/mpeg",
  "durationMs": 1842000,
  "participants": [
    {
      "speakerId": "speaker-1",
      "label": "Speaker A"
    },
    {
      "speakerId": "speaker-2",
      "label": "Speaker B"
    }
  ],
  "utterances": [
    {
      "id": "u001",
      "speakerId": "speaker-1",
      "startMs": 1250,
      "endMs": 4860,
      "pauseBeforeMs": 1250,
      "pauseAfterMs": 840,
      "overlap": false,
      "text": "We need to move the extraction API behind the contract.",
      "confidence": 0.96
    },
    {
      "id": "u002",
      "speakerId": "speaker-2",
      "startMs": 5700,
      "endMs": 8120,
      "pauseBeforeMs": 840,
      "pauseAfterMs": 0,
      "overlap": false,
      "text": "Agreed. The processor should remain replaceable.",
      "confidence": 0.94
    }
  ],
  "chunks": [
    {
      "chunkId": "conv-001-c01",
      "type": "conversation_turn",
      "content": "Speaker A: We need to move the extraction API behind the contract.",
      "sectionPath": [],
      "sourceElements": ["u001"],
      "pageStart": null,
      "pageEnd": null,
      "tokenCount": 16,
      "metadata": {
        "startMs": 1250,
        "endMs": 4860,
        "speakerId": "speaker-1"
      }
    }
  ]
}
```



------

## 19. Pauses

Pauses are useful conversational structure.

The extraction plane SHOULD distinguish:

text

```
pauseBefore
pauseAfter
silenceInterval
```



Example:

json

```
{
  "type": "pause",
  "startMs": 8120,
  "endMs": 10250,
  "durationMs": 2130
}
```



A pause is an observable audio property.

The extraction plane SHOULD NOT interpret it as:

text

```
speaker was uncertain
speaker disagreed
speaker was thinking
```



Those are semantic interpretations and belong downstream.

------

## 20. Simultaneous Talk / Overlap

Conversation extraction MUST NOT force overlapping speech into a single linear sequence.

Example:

text

```
Speaker A:  10.200s ---------------- 13.800s
Speaker B:             12.900s ---------------- 15.200s
                           | overlap |
```



Represent it explicitly:

json

```
{
  "id": "u021",
  "speakerId": "speaker-1",
  "startMs": 10200,
  "endMs": 13800,
  "text": "The important part is—"
},
{
  "id": "u022",
  "speakerId": "speaker-2",
  "startMs": 12900,
  "endMs": 15200,
  "text": "Yes, but the contract—"
}
```



The overlap relationship can be represented independently:

json

```
{
  "type": "overlap",
  "source": "u021",
  "target": "u022",
  "startMs": 12900,
  "endMs": 13800
}
```



This preserves evidence that both speakers were talking simultaneously.

------

## 21. Conversation Summarization

Summarization is an enrichment stage.

The transcript remains authoritative extraction evidence.

text

```
audio
  |
  +--> acoustic analysis
  +--> transcription
  +--> diarization
  +--> pause/overlap detection
  |
  +--> structured conversation
             |
             +--> LLM summary
```



Example:

json

```
{
  "type": "summary",
  "summaryId": "summary-001",
  "scope": {
    "startMs": 0,
    "endMs": 1842000
  },
  "content": "The participants agreed to keep extraction behind a deployment-neutral contract and test the OpenDataLoader PDF path as the first PoC.",
  "generator": {
    "type": "llm",
    "model": "model-reference"
  },
  "sourceRefs": [
    "u001",
    "u002"
  ]
}
```



The `sourceRefs` are important: generated summaries should be traceable to the extracted conversation.

------

## 22. Audio Feature Tags

Recommended request tags:

text

```
audio=transcription
audio=diarization
audio=pauses
audio=overlap
audio=summary
language=en
```



Result states might be:

json

```
{
  "features": {
    "transcription": "applied",
    "diarization": "applied",
    "pauses": "applied",
    "overlap": "partial",
    "summary": "applied"
  }
}
```



------

## 23. Image Extraction

Image processing should separate deterministic extraction from LLM/VLM interpretation.

text

```
image
  |
  +--> metadata
  +--> dimensions
  +--> EXIF where allowed
  +--> OCR
  |
  +--> VLM description
  +--> object/scene interpretation
```



Example:

json

```
{
  "type": "image",
  "mediaType": "image/jpeg",
  "width": 1920,
  "height": 1080,
  "ocr": {
    "text": "Q3 Revenue: €4.2M",
    "regions": [
      {
        "text": "Q3 Revenue: €4.2M",
        "bbox": [220, 90, 780, 170],
        "confidence": 0.97
      }
    ]
  },
  "description": {
    "text": "A presentation slide containing a Q3 revenue headline and a bar chart comparing quarterly revenue.",
    "generated": true
  },
  "chunks": [
    {
      "chunkId": "img-001-c01",
      "type": "image_ocr",
      "content": "Q3 Revenue: €4.2M",
      "sectionPath": [],
      "sourceElements": ["ocr-001"],
      "pageStart": null,
      "pageEnd": null,
      "tokenCount": 8
    },
    {
      "chunkId": "img-001-c02",
      "type": "image_description",
      "content": "A presentation slide containing a Q3 revenue headline and a bar chart comparing quarterly revenue.",
      "sectionPath": [],
      "sourceElements": ["desc-001"],
      "pageStart": null,
      "pageEnd": null,
      "tokenCount": 18,
      "metadata": {
        "generated": true,
        "generatorType": "vlm"
      }
    }
  ]
}
```



------

## 24. OCR vs Image Description

OCR answers:

> **What text is visibly present?**

Image description answers:

> **What is visually depicted?**

They are complementary.

Example:

text

```
Image
 |
 +--> OCR
 |     "Q3 Revenue: €4.2M"
 |
 +--> VLM
       "A business presentation slide showing quarterly revenue..."
```



The two outputs should remain separate so downstream consumers can decide how much trust to place in each.

------

## 25. Image Description Provenance

LLM/VLM-generated descriptions MUST carry provenance.

Recommended fields:

json

```
{
  "generated": true,
  "generatorType": "vlm",
  "model": "model-reference",
  "promptVersion": "image-description-v1",
  "confidence": 0.82,
  "sourceImageDigest": "..."
}
```



The extraction plane should not present a generated description as if it were source text.

------

## 26. Video Extraction

Video should be treated as a timeline containing multiple synchronized modalities.

text

```
video
 |
 +--> video metadata
 |
 +--> audio
 |     |
 |     +--> transcription
 |     +--> diarization
 |
 +--> frames
       |
       +--> OCR
       +--> visual analysis
       +--> scene detection
       +--> short-clip analysis
```



The core result should preserve temporal coordinates.

------

## 27. Video Scene Model

Example:

json

```
{
  "type": "video",
  "durationMs": 423000,
  "scenes": [
    {
      "sceneId": "scene-001",
      "startMs": 0,
      "endMs": 38100,
      "keyFrames": [
        {
          "timestampMs": 1200,
          "objectRef": "frames/001.jpg"
        },
        {
          "timestampMs": 18200,
          "objectRef": "frames/002.jpg"
        }
      ]
    }
  ],
  "chunks": [
    {
      "chunkId": "video-001-c01",
      "type": "video_scene",
      "content": "[Scene 0:00 - 0:38] Opening scene with introduction",
      "sectionPath": [],
      "sourceElements": ["scene-001"],
      "pageStart": null,
      "pageEnd": null,
      "tokenCount": 12,
      "metadata": {
        "startMs": 0,
        "endMs": 38100
      }
    }
  ]
}
```



A scene is an extraction boundary, not a business event.

------

## 28. Short-Clip Extraction

The video plane should support bounded clips rather than requiring the entire video to be interpreted by an LLM.

Example request:

json

```
{
  "type": "video-clip-request",
  "startMs": 125000,
  "endMs": 145000,
  "features": [
    "transcription",
    "ocr",
    "visual-description",
    "summary"
  ]
}
```



Result:

json

```
{
  "type": "video-clip",
  "startMs": 125000,
  "endMs": 145000,
  "transcript": [
    {
      "speakerId": "speaker-1",
      "startMs": 126200,
      "endMs": 130500,
      "text": "Here is the new extraction architecture."
    }
  ],
  "ocr": [
    {
      "timestampMs": 128400,
      "text": "Structured Content Extraction Plane"
    }
  ],
  "description": "A presenter is explaining an architecture diagram displayed on a screen.",
  "summary": "The presenter introduces the structured extraction plane and its processing boundary.",
  "chunks": [
    {
      "chunkId": "clip-001-c01",
      "type": "video_clip",
      "content": "[0:02:05-0:02:25] The presenter introduces the structured extraction plane and its processing boundary.",
      "sectionPath": [],
      "sourceElements": ["transcript-001", "ocr-001"],
      "pageStart": null,
      "pageEnd": null,
      "tokenCount": 20,
      "metadata": {
        "startMs": 125000,
        "endMs": 145000
      }
    }
  ]
}
```



This bounded model controls LLM/VLM cost and gives downstream consumers precise source references.

------

## 29. Cross-Modality Result Model

All modalities should share a common envelope.

json

```
{
  "schema": {
    "id": "synanton.extraction",
    "version": "1.0"
  },

  "operation": {
    "operationId": "op-001",
    "status": "COMPLETED"
  },

  "source": {
    "contentRefId": "content-001",
    "mediaType": "application/pdf",
    "sha256": "..."
  },

  "features": {},

  "metadata": {},

  "payload": {},

  "chunks": [],

  "flattenedText": null,

  "provenance": {}
}
```



Only `payload` changes shape by modality; `chunks` is a common field across all modalities.

------

## 30. Modality Payloads

text

```
StructuredPayload
 |
 +-- DocumentPayload
 |     |
 |     +-- elements
 |     +-- metadata
 |     +-- pages
 |     +-- chunks
 |
 +-- ConversationPayload
 |     |
 |     +-- participants
 |     +-- utterances
 |     +-- pauses
 |     +-- overlaps
 |     +-- summaries
 |     +-- chunks
 |
 +-- ImagePayload
 |     |
 |     +-- metadata
 |     +-- OCR regions
 |     +-- descriptions
 |     +-- chunks
 |
 +-- VideoPayload
       |
       +-- timeline
       +-- scenes
       +-- keyframes
       +-- transcript
       +-- OCR
       +-- clip analyses
       +-- chunks
```



This keeps the common contract stable while allowing modality-specific evolution.

------

## 31. Provenance Model

Every extracted or generated element should be traceable back to its source location where possible.

### Documents

text

```
source
  -> page
  -> bounding box
  -> element
```



### Audio

text

```
source
  -> start/end timestamp
  -> utterance
```



### Images

text

```
source
  -> bounding box
  -> OCR/description
```



### Video

text

```
source
  -> time interval
  -> frame
  -> visual/audio extraction
```



A common source locator can therefore be:

json

```
{
  "kind": "page",
  "page": 4,
  "bbox": [100, 200, 400, 300]
}
```



or:

json

```
{
  "kind": "time",
  "startMs": 125000,
  "endMs": 145000
}
```



For chunks, `sourceElements` provides the link back to the extraction provenance.

------

## 32. Extraction vs Knowledge Processing

The boundary should remain explicit.

### Extraction

text

```
PDF
  -> heading
  -> paragraph
  -> table

Audio
  -> speaker
  -> utterance
  -> pause
  -> overlap

Image
  -> text
  -> visual description

Video
  -> scene
  -> frame
  -> transcript
  -> clip summary
```



### Semantic Chunking

text

```
Document elements
  -> structure tree
  -> semantic chunks with sectionPath
  -> provenance links
```



### Knowledge processing

text

```
heading
  -> document concept

utterance
  -> business statement

image description
  -> entity candidate

video clip
  -> event candidate

table
  -> business facts

chunks
  -> retrieval / RAG / summarization
```



The knowledge layer can reject, enrich, resolve, or combine extracted observations and chunks.

------

## 33. End-to-End Example — PDF with Semantic Chunking

text

```
SharePoint / FileNet / local FS
              |
              v
        Content Object
              |
              v
    ExtractionRequest
              |
              v
+--------------------------------+
| Structured Extraction Plane    |
|                                |
| OpenDataLoader PDF PoC         |
|                                |
| text                           |
| layout                         |
| tables                         |
| images                         |
| OCR                            |
| formulas                       |
| image descriptions             |
+---------------+----------------+
                |
                v
       Synanton StructuredPayload
                |
                +--> flattenedText
                |
                +--> document elements
                |
                +--> provenance
                |
                v
+--------------------------------+
| Semantic Chunking Layer        |
|                                |
| Structure Builder              |
|   elements -> section tree     |
|                                |
| Semantic Chunker               |
|   section -> semantic chunks   |
|                                |
| Chunk Enricher                 |
|   sectionPath, sourceElements  |
+---------------+----------------+
                |
                v
         Chunks + Embeddings
                |
                +--> vector index
                |
                +--> summarization
                |
                +--> citation
                |
                v
        Knowledge Processing
                |
                v
        Search / Relationships
        / Ontology / Business Logic
```



------

## 34. End-to-End Example — Audio Meeting

text

```
meeting.mp3
     |
     v
Extraction Plane
     |
     +--> transcription
     +--> diarization
     +--> pauses
     +--> overlap
     |
     +--> conversation structure
              |
              +--> LLM summary
              |
              v
       StructuredPayload
              |
              +--> utterances
              |
              +--> summaries
              |
              v
       Semantic Chunking
              |
              +--> turn-based chunks
              |
              +--> summary chunks
              |
              v
       Knowledge Processing
```



------

## 35. End-to-End Example — Image

text

```
photo.jpg
   |
   v
Extraction Plane
   |
   +--> image metadata
   +--> OCR
   +--> VLM description
   |
   v
ImagePayload
   |
   +--> OCR evidence
   +--> visual observation
   |
   v
Semantic Chunking
   |
   +--> OCR text chunks
   |
   +--> description chunks
   |
   v
Knowledge Processing
```



------

## 36. End-to-End Example — Video Clip

text

```
recording.mp4
      |
      v
Scene detection
      |
      +--> clip 00:02:05 - 00:02:25
                    |
                    +--> speech
                    +--> OCR
                    +--> key frames
                    +--> visual description
                    +--> summary
                    |
                    v
             VideoClipPayload
                    |
                    +--> transcript
                    |
                    +--> summary
                    |
                    v
             Semantic Chunking
                    |
                    +--> clip chunk with metadata
                    |
                    v
             Knowledge Processing
```



------

## 37. Adapter Architecture

The extraction plane should use adapters to isolate implementation-specific processors.

text

```
                 Extraction API
                       |
                       v
              Extraction Router
                       |
        +--------------+--------------+
        |              |              |
        v              v              v
 DocumentAdapter   AudioAdapter   ImageAdapter
        |              |              |
        v              v              v
 OpenDataLoader    ASR/diarizer    OCR/VLM
        |
        +--> PDF
```



For video:

text

```
VideoAdapter
   |
   +--> media demux
   +--> frame extraction
   +--> AudioAdapter
   +--> ImageAdapter
   +--> scene processor
   +--> clip processor
```



Adapters translate processor-native output into the Synanton modality schemas.

------

## 38. OpenDataLoader as a PoC Adapter

The PDF PoC should therefore be:

text

```
                    Synanton
                       |
              ExtractionRequest
                       |
                       v
             +-------------------+
             | PDF Adapter       |
             +---------+---------+
                       |
                       v
             OpenDataLoader PDF
                       |
              +--------+--------+
              |        |        |
              v        v        v
             JSON   Markdown   HTML
              |
              v
       Synanton PDF normalizer
              |
              v
        DocumentPayload
              |
              v
       Semantic Chunker
              |
              v
        Chunks + Embeddings
```



This design means OpenDataLoader can later be replaced by:

text

```
another PDF parser
Synanton-native PDF processor
specialized OCR pipeline
external document extraction service
```



without changing the Synanton extraction API.

------

## 39. PDF PoC Feature Matrix

The first PoC should validate:

| Feature                  | PoC      | Result                          |
| ------------------------ | -------- | ------------------------------- |
| Text extraction          | Yes      | structured text                 |
| Reading order            | Yes      | ordered elements                |
| Bounding boxes           | Yes      | source coordinates              |
| Headings                 | Yes      | heading hierarchy               |
| Lists                    | Yes      | list structure                  |
| Tables                   | Yes      | structured tables               |
| Images                   | Yes      | image references/coordinates    |
| OCR                      | Yes      | OCR text for scanned pages      |
| Formula extraction       | Yes      | LaTeX                           |
| Image/chart description  | Yes      | generated description           |
| Tagged PDF structure     | Optional | native semantic structure       |
| Sanitization             | Optional | redacted output                 |
| Markdown                 | Yes      | RAG-friendly projection         |
| HTML                     | Optional | display projection              |
| Annotated PDF            | Optional | visual debugging                |
| **Semantic Chunking**    | **Yes**  | **structure-aware chunks**      |
| **sectionPath**          | **Yes**  | **heading hierarchy preserved** |
| **Table as first-class** | **Yes**  | **atomic table chunks**         |

OpenDataLoader documents these capabilities and its current output formats; exact  support can vary by deterministic versus hybrid mode.  citeturn0search0turn0search2

------

## 40. PoC Acceptance Criteria

The PDF PoC is successful when:

1. A PDF is referenced through the Synanton extraction contract.
2. The PDF adapter invokes OpenDataLoader.
3. OpenDataLoader produces structured JSON.
4. JSON elements retain page and bounding-box provenance.
5. Text, headings, lists, tables and images can be represented.
6. OCR can be requested explicitly.
7. Formula and image-description enrichment can be requested explicitly.
8. Feature state records what was actually applied.
9. OpenDataLoader-specific fields are normalized behind the adapter.
10. A `DocumentPayload` is returned.
11. `flattenedText` can be generated without reparsing the source.
12. The operation is idempotent.
13. The operation can be asynchronous.
14. The source PDF is never modified by extraction.
15. The Synanton contract contains no dependency on OpenDataLoader classes or internal processing topology.
16. **Semantic chunking is applied to the structured document.**
17. **`sectionPath` is present in every chunk.**
18. **Tables are never split across chunks.**
19. **Chunks can be traced back to source elements.**

------

## 41. Recommended Canonical Example

The following should be used as the first end-to-end contract example.

### Request

json

```
{
  "contentRefId": "content-pdf-001",
  "mediaType": "application/pdf",
  "tags": {
    "source-system": "local-library",
    "document-class": "technical-book",
    "extract": "text,layout,tables,images,formulas",
    "ocr": "include",
    "image-description": "include",
    "chunking": "structure-aware",
    "chunking-max-tokens": "512"
  },
  "options": {
    "ocr": true,
    "layout": true,
    "tables": true,
    "embeddedImages": true,
    "imageDescription": true,
    "formulas": true
  },
  "priority": "NORMAL",
  "expiresAt": "2026-08-24T18:00:00Z",
  "idempotencyKey": "content-pdf-001-v1"
}
```



### Result

json

```
{
  "schema": {
    "id": "synanton.document",
    "version": "1.0"
  },

  "operation": {
    "operationId": "op-pdf-001",
    "status": "COMPLETED",
    "progress": 1.0
  },

  "source": {
    "contentRefId": "content-pdf-001",
    "mediaType": "application/pdf",
    "sha256": "abc123..."
  },

  "features": {
    "text": "applied",
    "layout": "applied",
    "tables": "applied",
    "images": "applied",
    "ocr": "applied",
    "formulas": "applied",
    "image-description": "applied",
    "chunking": "applied"
  },

  "payload": {
    "type": "document",
    "metadata": {},
    "elements": [
      {
        "id": "p1-e01",
        "type": "heading",
        "source": {
          "page": 1,
          "bbox": [72, 700, 540, 730]
        },
        "content": "Structured Content Extraction Plane"
      },
      {
        "id": "p1-e02",
        "type": "paragraph",
        "source": {
          "page": 1,
          "bbox": [72, 640, 540, 690]
        },
        "content": "The extraction plane converts raw enterprise content into structured representations."
      },
      {
        "id": "p2-e03",
        "type": "table",
        "source": {
          "page": 2,
          "bbox": [72, 390, 540, 580]
        },
        "content": {
          "columns": ["Feature", "Purpose"],
          "rows": [
            ["OCR", "Extract text from scanned pages"],
            ["Layout", "Preserve document reading order"],
            ["Tables", "Preserve tabular structure"]
          ]
        }
      },
      {
        "id": "p2-e04",
        "type": "image",
        "source": {
          "page": 2,
          "bbox": [72, 120, 540, 350]
        },
        "content": {
          "objectRef": "extracted-images/p2-e04.jpg",
          "description": "Architecture diagram showing raw content entering an extraction plane and producing structured payloads."
        }
      }
    ]
  },

  "chunks": [
    {
      "chunkId": "doc-001-c01",
      "type": "heading",
      "content": "Structured Content Extraction Plane",
      "sectionPath": [],
      "heading": "Structured Content Extraction Plane",
      "sourceElements": ["p1-e01"],
      "pageStart": 1,
      "pageEnd": 1,
      "tokenCount": 7
    },
    {
      "chunkId": "doc-001-c02",
      "type": "paragraph",
      "content": "The extraction plane converts raw enterprise content into structured representations.",
      "sectionPath": [],
      "sourceElements": ["p1-e02"],
      "pageStart": 1,
      "pageEnd": 1,
      "tokenCount": 14
    },
    {
      "chunkId": "doc-001-c03",
      "type": "table",
      "content": "Feature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure",
      "structuredContent": {
        "table": {
          "headers": ["Feature", "Purpose"],
          "rows": [
            ["OCR", "Extract text from scanned pages"],
            ["Layout", "Preserve document reading order"],
            ["Tables", "Preserve tabular structure"]
          ]
        }
      },
      "sectionPath": [],
      "sourceElements": ["p2-e03"],
      "pageStart": 2,
      "pageEnd": 2,
      "tokenCount": 28
    },
    {
      "chunkId": "doc-001-c04",
      "type": "image",
      "content": "Architecture diagram showing raw content entering an extraction plane and producing structured payloads.",
      "sectionPath": [],
      "sourceElements": ["p2-e04"],
      "pageStart": 2,
      "pageEnd": 2,
      "tokenCount": 17,
      "metadata": {
        "generated": true,
        "generatorType": "vlm"
      }
    }
  ],

  "flattenedText": "Structured Content Extraction Plane\nThe extraction plane converts raw enterprise content into structured representations.\nFeature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure",

  "provenance": {
    "processor": "opendataloader-pdf",
    "processorVersion": "2.5.0",
    "generatedAt": "2026-08-24T16:00:00Z",
    "chunkingStrategy": "structure-aware-v1",
    "chunkerVersion": "1.0"
  }
}
```



The `2.5.0` processor version above is an example of recording processor  provenance; the OpenDataLoader project currently lists v2.5.0 as its  latest release in the GitHub releases page. citeturn0search5

------

## 42. Implementation Boundary

The implementation should be split into three layers.

text

```
+------------------------------------------------------+
| Synanton Extraction Contract                        |
|                                                      |
| Request / Operation / Result / Tags / Errors        |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
| Modality Adapters                                   |
|                                                      |
| PDF | Text | EPUB | HTML | Audio | Image | Video   |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
| Processor Implementations                           |
|                                                      |
| OpenDataLoader | OCR | ASR | diarization | VLM      |
| scene detection | frame extraction | other engines |
+------------------------------------------------------+
```



Semantic chunking operates on the normalized output of the adapters, before knowledge processing.

text

```
+------------------------------------------------------+
| Semantic Chunking Layer                             |
|                                                      |
| Structure Builder | Semantic Chunker | Enricher     |
+------------------------------------------------------+
```



This separation is the key design decision.

------

## 43. What the Extraction Plane Should Not Do

The extraction plane SHOULD NOT become responsible for:

- ontology management;
- entity resolution;
- canonical business entities;
- business rules;
- relationship inference;
- workflow decisions;
- ranking;
- authorization decisions unrelated to source access;
- domain-specific classifications unless explicitly requested as extraction enrichment;
- permanent knowledge storage.

For example:

text

```
OCR:
"Acme Corporation"
```



is extraction.

Resolving:

text

```
"Acme Corporation"
       |
       v
Company#1234
```



is knowledge processing/entity resolution.

Likewise:

text

```
transcript:
"We should delay the deployment."
```



is extraction.

Interpreting this as:

text

```
decision = deployment-delay
```



is downstream semantic processing.

------

## 44. Relationship to Lucentrix

The extraction plane consumes content made available by the ingestion/source layer.

A representative Synanton flow is:

text

```
                    Source Systems
                         |
        +----------------+----------------+
        |                |                |
      FileNet         SharePoint       Local FS
        |                |                |
        +----------------+----------------+
                         |
                         v
                    Lucentrix
                source connectors
                         |
                         v
                 Content Objects
                         |
                         v
          Structured Content Extraction
                         |
              +----------+----------+
              |          |          |
             PDF       Audio      Image/Video
              |          |          |
              +----------+----------+
                         |
                         v
                 StructuredPayload
                         |
                         v
              Semantic Chunking Layer
                         |
              +----------+----------+
              |          |          |
           Chunks     Summaries   Metadata
              |          |          |
              +----------+----------+
                         |
                         v
                 Knowledge Platform
```



Lucentrix knows **how to retrieve the source content**.

The extraction plane knows **how to turn retrieved content into structured content**.

The chunking layer knows **how to represent that structure for downstream tasks**.

The knowledge platform knows **how to interpret and use that structured content**.

------

## 45. Local Books Library Example

For a local ebook/PDF library:

text

```
/books
├── architecture
│   ├── clean-architecture.pdf
│   └── domain-driven-design.epub
├── ai
│   ├── transformers.pdf
│   └── llm-engineering.epub
└── history
    └── european-history.pdf
```



Lucentrix/source ingestion produces content references:

text

```
content-001 -> /books/architecture/clean-architecture.pdf
content-002 -> /books/architecture/domain-driven-design.epub
...
```



The extraction plane processes each reference according to media type:

text

```
PDF  -> PDF adapter
EPUB -> EPUB adapter
```



The result becomes normalized structured content while preserving source provenance.

The semantic chunking layer then transforms the structured content into task-optimized chunks.

------

## 46. Operational Sequence

A typical asynchronous flow is:

text

```
1. Source connector discovers content
          |
2. Content object created/versioned
          |
3. Extraction request submitted
          |
4. Idempotency checked
          |
5. Capacity/admission checked
          |
6. Operation created
          |
7. Modality adapter selected
          |
8. Processor executes
          |
9. Processor result normalized
          |
10. Feature states recorded
          |
11. StructuredPayload persisted
          |
12. Semantic chunking applied
          |
13. Chunks persisted
          |
14. Operation marked COMPLETED/PARTIAL/FAILED
          |
15. Knowledge processing consumes chunks
```



------

## 47. Key Design Decision

The central design decision is:

> **Use OpenDataLoader PDF as a replaceable PDF processor for the PoC, while  making Synanton's Structured Content Extraction Plane the stable  architectural boundary. Semantic chunking operates on the normalized  output, not on the raw processor output.**

The same principle should apply to all other modalities.

text

```
PDF       -> OpenDataLoader adapter -> normalize -> chunk
Audio     -> ASR/diarization adapter -> normalize -> chunk
Image     -> OCR/VLM adapter -> normalize -> chunk
Video     -> media/scene/audio/image adapters -> normalize -> chunk
```



No processor should become the API.

------

## 48. Future Evolution

The extraction plane can evolve independently.

Examples:

text

```
v1
  PDF + text

v1.1
  OCR + tables + images

v1.2
  audio transcription + diarization

v1.3
  image VLM analysis

v1.4
  video scenes + clips

v2
  specialized GPU extraction cluster

v3
  delegated/external processors
```



The Synanton contract remains:

text

```
request
  -> operation
  -> status
  -> structured payload
  -> chunks
```



The implementation behind it can change.

------

## 49. Decision Summary

### Adopt

- Structured Content Extraction Plane;
- modality-neutral extraction contract;
- modality-specific payloads;
- explicit feature states;
- capability/business/provenance tags;
- idempotent asynchronous operations;
- expiration;
- priority intent;
- capacity/admission;
- provenance;
- flattened-text compatibility projection;
- processor adapters;
- OpenDataLoader PDF PoC;
- audio temporal structure;
- pause and overlap preservation;
- image OCR plus VLM description;
- video scene and short-clip extraction;
- **Semantic chunking layer;**
- **Structure builder;**
- **`sectionPath` in every chunk;**
- **First-class table chunks;**
- **Provenance links (`sourceElements`) in chunks.**

### Preserve

- raw content as authoritative source;
- extraction/knowledge boundary;
- deployment topology independence;
- structured payload extensibility;
- no mandatory webhook dependency;
- no consumer reparsing.

### Avoid

- exposing worker topology;
- making OpenDataLoader the Synanton API;
- flattening all modalities into text;
- treating LLM-generated descriptions/summaries as source evidence;
- embedding ontology/entity resolution in extraction;
- coupling extraction to a specific scheduler or hardware pool;
- **making `flattenedText` the only input to chunking;**
- **splitting tables across chunks;**
- **losing heading hierarchy in chunks.**

------

## 50. Conclusion & Key Architectural Statement

The key architectural statement for Synanton v1.22 is:

> **Structured extraction is the canonical input to semantic chunking. Chunk  boundaries SHOULD follow document semantics—section hierarchy, lists,  tables, figures, and other structural elements—while token/size limits  provide a secondary constraint and fallback. `flattenedText` MUST NOT be the only input available to the chunking stage.**

This design gives Synanton a clean, scalable separation between:

1. **Extraction** — What is in the document (v1.21)
2. **Document Understanding/Structuring** — How it is organized (v1.22)
3. **Task-Specific Representation** — How it is consumed (v1.22)

It fully leverages the provenance and semantic richness already provided  by the v1.21 OpenDataLoader integration, transforming Synanton's  ingestion and search architecture from a naive text pipeline into a  truly structure-aware knowledge engine.

------

## 51. References

1. Synanton v1.21 Structured Content Extraction Plane proposal supplied as the basis for this design.
2. Synanton v1.20 Architecture design document.
3. OpenDataLoader PDF repository and documentation. urlopendataloader-project/opendataloader-pdfhttps://github.com/opendataloader-project/opendataloader-pdf
4. OpenDataLoader PDF JSON schema. urlOpenDataLoader PDF JSON schemahttps://github.com/opendataloader-project/opendataloader-pdf/blob/main/schema.json
5. OpenDataLoader PDF options. urlOpenDataLoader PDF optionshttps://github.com/opendataloader-project/opendataloader-pdf/blob/main/options.json
6. OpenDataLoader Java examples. urlOpenDataLoader PDF exampleshttps://github.com/opendataloader-project/opendataloader-pdf-examples