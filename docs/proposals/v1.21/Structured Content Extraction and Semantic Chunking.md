## Structured Content Extraction and Semantic Chunking

The current v1.21 design already gives you the right foundation: the normalized document result preserves `elements`, including headings, paragraphs, tables, lists, images, captions and formulas, while `flattenedText` is retained as a derived representation. 

OpenDataLoader is particularly suitable for proving this because its JSON output explicitly exposes semantic element types, heading levels, reading order and bounding boxes, rather than just returning a text stream. 

### I would model the ingestion pipeline like this

```
PDF
 │
 ▼
Structured Content Extraction
 │
 │  heading
 │  paragraph
 │  list
 │  table
 │  image
 │  caption
 │  formula
 │  page / bbox
 │
 ▼
Document Structure Tree
 │
 ▼
Semantic Chunker
 │
 ├── section chunks
 ├── subsection chunks
 ├── table chunks
 ├── figure/image chunks
 └── fallback token/size splitting
 │
 ├───────────────────────┐
 ▼                       ▼
Embedding               LLM enrichment
 │                       │
 ▼                       ├── section summary
Vector index             ├── document summary
                         └── metadata
```

The important architectural point is that **the chunker should operate on the structured representation, not on `flattenedText`**.

## Why this is better than traditional text chunking

Suppose the PDF contains:

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

```
chunk 17:
"The GPU execution plane ... 3.1 GPU Gateway ...
The gateway provides ... Table 4 ... Interactive ...
Batch ..."

chunk 18:
"30m Normal ... 3.2 Scheduling ..."
```

You lose the document's semantic boundaries.

A structure-aware chunker can instead produce:

```
Chunk A
type: section
heading: "3. GPU Execution Plane"
path: ["3. GPU Execution Plane"]
content:
  introduction paragraphs

Chunk B
type: section
heading: "3.1 GPU Gateway"
path:
  ["3. GPU Execution Plane", "3.1 GPU Gateway"]
content:
  paragraphs

Chunk C
type: table
path:
  ["3. GPU Execution Plane"]
caption:
  "Table 4: Execution classes"
content:
  complete table

Chunk D
type: section
heading: "3.2 Scheduling"
path:
  ["3. GPU Execution Plane", "3.2 Scheduling"]
content:
  paragraphs
```

That is **much more useful for both retrieval and downstream LLM processing**.

------

# One important correction: don't make "one heading = one chunk"

I would avoid making the rule too simplistic.

A heading defines a **semantic boundary**, but not necessarily the final embedding chunk.

For example:

```
2. Architecture
   2.1 Control Plane
       8 paragraphs
       2 tables
       3,500 tokens
```

That section is semantically coherent but too large for one embedding.

So I would make chunking **hierarchical and constraint-based**:

```
Document
  │
  ├── Section
  │     │
  │     ├── subsection
  │     │      ├── paragraph
  │     │      ├── paragraph
  │     │      └── table
  │     │
  │     └── subsection
  │
  └── Section
```

Then:

1. **Prefer semantic boundaries.**
2. **Keep related elements together.**
3. **Never split a table arbitrarily if possible.**
4. **Split oversized sections at paragraph/list boundaries.**
5. **Only use token-based splitting as the final fallback.**
6. **Carry the heading hierarchy into every resulting chunk.**

This gives you the best of both worlds: semantic coherence **and** bounded chunk size.

------

# Tables deserve special treatment

I would explicitly make tables a first-class chunk type.

For example:

```
{
  "chunkId": "doc1-p12-table3",
  "type": "table",
  "content": {
    "caption": "GPU execution classes",
    "headers": ["Class", "Timeout", "Priority"],
    "rows": [
      ["Interactive", "60s", "High"],
      ["Batch", "30m", "Normal"]
    ]
  },
  "metadata": {
    "sectionPath": [
      "GPU Execution Plane",
      "Scheduling"
    ],
    "pageStart": 12,
    "pageEnd": 12
  }
}
```

Then generate an embedding from a representation such as:

```
GPU Execution Plane > Scheduling

Table: GPU execution classes

Class: Interactive
Timeout: 60s
Priority: High

Class: Batch
Timeout: 30m
Priority: Normal
```

This is significantly better than embedding whatever textual order the PDF parser happens to produce.

OpenDataLoader already exposes tables as semantic elements, including their structure, and its JSON representation carries bounding-box information. 

------

# I would also introduce `sectionPath` into the Synanton chunk model

This is probably the most valuable addition to the PoC.

For example:

```
{
  "chunkId": "doc-001-c17",
  "documentId": "doc-001",

  "type": "paragraph",

  "content": "The GPU execution plane is physically separated...",

  "sectionPath": [
    "Architecture",
    "GPU Execution Plane",
    "GPU Gateway"
  ],

  "heading": "GPU Gateway",

  "pageStart": 7,
  "pageEnd": 8,

  "sourceElements": [
    "p7-e12",
    "p7-e13",
    "p8-e02"
  ]
}
```

This creates an excellent bridge between:

**source PDF → extraction → chunk → embedding → retrieval → citation**

rather than treating the embedding as an anonymous piece of text.

The v1.21 proposal already has stable element IDs, page/bounding-box provenance and a normalized `elements` collection, so this fits the existing design rather than requiring a new extraction model. 

------

# Embedding and summarization should use the same structure differently

This is another distinction I would put into the design.

### Embeddings

Optimize for **retrieval granularity**:

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

A subsection might produce several embedding chunks if it is large.

### Summarization

Optimize for **hierarchical context**:

```
paragraphs
   ↓
section summary
   ↓
chapter summary
   ↓
document summary
```

So you can potentially create:

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

This is much more powerful than sending the entire flattened PDF to an LLM and asking it to summarize.

Interestingly, this is consistent with the v1.21 principle already used for audio: extraction evidence remains authoritative, while summarization is treated as a downstream enrichment stage. 

------

# I would add one new conceptual layer to v1.21

I would explicitly introduce:

## Semantic Content Structuring / Chunking

between extraction and consumers.

Currently the proposal essentially establishes:

```
Raw Content
    ↓
Extraction Plane
    ↓
Structured Content
```

I would extend the ingestion POC to demonstrate:

```
Raw Content
    ↓
Structured Content Extraction Plane
    ↓
Normalized Document Structure
    ↓
Semantic Chunking / Structuring
    ↓
 ┌───────────────┬────────────────┐
 ↓               ↓                ↓
Embedding      Summarization    Search metadata
```

I would **not** put this logic into the extraction plane itself.

The extraction plane should answer:

> "What is in this document and what is its structure?"

The chunking layer should answer:

> "How should this structure be represented for a particular downstream task?"

That separation is architecturally important because the same extracted document may need different chunking strategies for:

- vector search
- RAG
- summarization
- classification
- entity extraction
- citation generation
- re-ranking

------

## Recommended PoC

For your current Synanton ingestion work, I would make the first implementation deliberately small:

### Input

A PDF with:

- title
- 2–3 heading levels
- paragraphs
- lists
- at least one table
- figure/caption
- enough content for some sections to exceed the target chunk size

### Stage 1 — OpenDataLoader

Produce:

```
JSON + Markdown
```

The project explicitly supports both, with JSON carrying structured semantic elements and Markdown intended for clean LLM/RAG context. 

### Stage 2 — Synanton normalization

Convert to:

```
Document
 ├── metadata
 └── elements[]
```

as already specified by v1.21. 

### Stage 3 — Structure builder

Build:

```
Document
 └── Section
      └── Section
           ├── Paragraph
           ├── Paragraph
           ├── Table
           └── Figure
```

### Stage 4 — Semantic chunker

Produce:

```
{
  "chunkId": "...",
  "type": "section",
  "content": "...",
  "sectionPath": ["3", "3.1"],
  "sourceElements": ["p7-e03", "p7-e04"],
  "pageStart": 7,
  "pageEnd": 8
}
```

with a configurable maximum token/character budget.

### Stage 5 — Embedding

Generate embeddings for chunks.

### Stage 6 — Retrieval experiment

Compare:

**A.** traditional fixed-size chunking

vs.

**B.** structure-aware chunking.

Measure at least:

- retrieval precision
- retrieval recall
- top-k relevance
- chunk count
- average chunk size
- number of cross-section chunks
- table retrieval quality
- citation/source reconstruction

That comparison would make this more than an implementation demo—it would provide evidence that **structured extraction actually improves Synanton's ingestion/search architecture**.

The key architectural statement to v1.21 is:

> **Structured extraction is the canonical input to semantic chunking. Chunk boundaries SHOULD follow document semantics—section hierarchy, lists, tables, figures and other structural elements—while token/size limits provide a secondary constraint and fallback. `flattenedText` MUST NOT be the only input available to the chunking stage.**

That gives Synanton a clean separation between **extraction**, **document understanding/structuring**, and **task-specific representation**, while making very good use of what OpenDataLoader already provides. 