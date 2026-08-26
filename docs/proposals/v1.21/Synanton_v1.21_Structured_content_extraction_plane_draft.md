# Synanton Structured Content Extraction Plane - Multimodal Extraction Design

**Document ID:** SNTP-7-DESIGN-SCEP-1.21  
**Date:** 2026-08-24  
**Status:** PROPOSED - ARCHITECTURAL DESIGN IN PROGRESS  
**Basis:** Synanton v1.21 Structured Content Extraction Plane proposal  
**Purpose:** Define a concrete multimodal extraction model and PoC examples while preserving the deployment-neutral extraction contract.

---

## 1. Executive Summary

The Structured Content Extraction Plane is the boundary between **raw content** and **structured content that can be consumed by Synanton knowledge processing**.

The extraction plane is deliberately a black box from the Synanton platform perspective. Synanton defines the request, lifecycle, metadata, tags, capabilities, structured result, and error contract; the extraction plane decides how the work is performed.

The plane covers multiple content domains:

```text
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
              +---------------+----------------+
              |               |                |
              v               v                v
         flattenedText   structure       modality data
              |               |                |
              +---------------+----------------+
                              |
                              v
                     Knowledge Processing
```

The extraction plane answers:

> **What is present in this artifact, and what structure can be reliably extracted from it?**

Knowledge processing answers:

> **What does the extracted content mean in the Synanton domain?**

This distinction is important. OCR, transcription, layout reconstruction, speaker diarization, image description, scene detection, and short-clip summarization are extraction capabilities. Entity resolution, ontology assignment, business classification, relationship inference, and workflow decisions belong downstream.

---

# 2. Architectural Principles

## 2.1 Contract over topology

The extraction contract MUST remain stable whether extraction is:

- embedded in Synanton;
- co-located with Synanton;
- deployed as a separate service;
- deployed as a horizontally scaled extraction cluster;
- delegated to specialized processors;
- routed through CPU/GPU workers;
- routed to another extraction provider.

```text
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

## 2.2 Source remains authoritative

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

## 2.3 Structure before meaning

Extraction SHOULD preserve observable structure before attempting interpretation.

For example, a PDF should preserve:

```text
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

```text
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

## 2.4 LLM/VLM enrichment is an extraction stage, not the knowledge layer

LLMs and VLMs MAY be used when deterministic extraction is insufficient.

Examples:

- describe a chart;
- describe an image;
- summarize a conversation;
- identify a video scene;
- interpret visual content.

The generated result MUST be represented as an explicitly typed extraction artifact with provenance and confidence where appropriate.

The generated interpretation MUST NOT silently become canonical business knowledge.

---

# 3. Content Domains

The initial extraction plane should support five major domains.

| Domain | Typical inputs | Primary extraction | Optional enrichment |
|---|---|---|---|
| Documents | PDF, TXT, EPUB, HTML | text, structure, layout, metadata | OCR, tables, formulas, image descriptions, summaries |
| Audio | WAV, MP3, M4A, meeting recordings | transcription, timestamps | diarization, pauses, overlap, conversation summary |
| Images | PNG, JPEG, TIFF, screenshots | image metadata, OCR | object/scene description, chart interpretation |
| Video | MP4, WebM, MOV | metadata, audio/video streams, key frames | transcription, OCR, scene detection, short-clip summaries |

The common result model should not force every modality into a document abstraction.

Instead:

```text
StructuredPayload
    |
    +-- DocumentPayload
    +-- ConversationPayload
    +-- ImagePayload
    +-- VideoPayload
```

A modality can expose a `flattenedText` compatibility projection when meaningful.

---

# 4. Common Extraction Contract

The existing v1.21 contract remains the architectural boundary.

Illustrative request:

```java
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

```text
contentRefId
object reference
media type
size
checksum
```

### Extraction options

Capabilities requested by the caller:

```text
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

```text
capability tags
business tags
provenance tags
```

---

# 5. Tag Model

Tags are important because multimodal extraction is not one fixed operation.

## 5.1 Capability tags

Capability tags request additional extraction features.

Examples:

```text
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
```

These tags express **what the caller wants**, not which processor must be used.

## 5.2 Business tags

Business tags travel with the request and result but remain opaque to the extraction plane.

Examples:

```text
tenant=acme
department=legal
case-id=CASE-1842
source-system=sharepoint
document-class=contract
```

The extraction plane SHOULD preserve these values.

It SHOULD NOT infer business semantics from them.

## 5.3 Result feature tags

The result SHOULD explicitly report what was actually produced.

Example:

```text
feature.text=applied
feature.layout=applied
feature.tables=applied
feature.ocr=not-applicable
feature.images=applied
feature.image-description=applied
```

This is preferable to assuming that a requested feature was successfully executed.

## 5.4 Provenance tags

Provenance tags identify how a particular result was created.

Examples:

```text
processor=opendataloader-pdf
processor-version=2.5.0
mode=deterministic
schema=synanton.document.v1
schema-version=1
```

For generated content:

```text
generated=true
generator-type=vlm
```

The precise processor identity MAY be omitted from the public API if Synanton wants a completely implementation-neutral contract, but it is useful diagnostic metadata.

---

# 6. Feature State

Every requested capability SHOULD have an explicit state:

```text
requested
applied
not-requested
not-applicable
unsupported
failed
partial
```

Example:

```json
{
  "featureStates": {
    "text": "applied",
    "layout": "applied",
    "tables": "applied",
    "ocr": "not-applicable",
    "image-description": "applied"
  }
}
```

This avoids a dangerous ambiguity:

```text
ocr=true
```

does not tell the caller whether OCR was:

- requested;
- executed;
- unnecessary;
- unsupported;
- failed.

---

# 7. Document Extraction

## 7.1 Supported document families

The document domain initially covers:

```text
PDF
plain text
EPUB / ebook
HTML
```

Additional document formats can be added without changing the extraction contract.

## 7.2 Common document result

A document result SHOULD expose:

```json
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

```json
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

---

# 8. PDF Processing PoC - OpenDataLoader PDF

For the PDF PoC, use:

urlopendataloader-project/opendataloader-pdfhttps://github.com/opendataloader-project/opendataloader-pdf

OpenDataLoader PDF currently provides structured JSON, Markdown and HTML output; its JSON representation includes semantic element types and bounding boxes. The documented capabilities include reading-order extraction, headings, lists, tables, images, OCR for scanned PDFs in hybrid mode, formulas, chart/image descriptions in hybrid mode, Tagged PDF structure extraction, and content-safety filtering. citeturn0search0turn0search3

The PoC should therefore use OpenDataLoader as an **implementation behind the Structured Content Extraction Plane**, not as the Synanton extraction contract.

## 8.1 PoC invocation

Python example:

```python
import opendataloader_pdf

opendataloader_pdf.convert(
    input_path=["sample.pdf"],
    output_dir="output/",
    format="json,markdown"
)
```

The project documents Python, Node.js and Java usage. Its Java examples generate JSON, Markdown and annotated PDF output. citeturn0search0turn0search1

For a PoC with a complex/scanned PDF, hybrid mode can be enabled. OCR is supported in hybrid mode and the project documents `--force-ocr` and language selection. citeturn0search0

---

# 9. Example Source PDF

Assume the PoC input is:

```text
library/
└── enterprise-architecture/
    └── extraction-plane-overview.pdf
```

The PDF contains:

```text
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

```json
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
    "image-description": "include"
  },
  "priority": "NORMAL",
  "expiresAt": "2026-08-24T18:00:00Z",
  "idempotencyKey": "pdf-01J-PDF-0001-v7"
}
```

---

# 10. OpenDataLoader Raw JSON Example

The exact OpenDataLoader schema is implementation-specific and MUST NOT be copied directly into the Synanton public contract.

A representative PoC output is:

```json
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

**Important:** the values above are a **representative PoC example**, not a claim that these exact elements occur in a particular sample PDF. The OpenDataLoader project documents the element types, fields, and bounding-box model; actual output depends on the input PDF. citeturn0search0turn0search3

---

# 11. Synanton Normalized PDF Result

Synanton should normalize processor-specific output into its own modality contract.

```json
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
    "image-description": "applied"
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

  "flattenedText": "Structured Content Extraction Plane\nThe extraction plane converts raw enterprise content into structured representations.\nFeature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure"
}
```

The important architectural distinction is:

```text
OpenDataLoader JSON
       |
       | adapter / normalizer
       v
Synanton StructuredPayload
```

This prevents OpenDataLoader's field naming or schema evolution from becoming a Synanton platform dependency.

---

# 12. PDF Feature Tags

The PoC should demonstrate the following feature-tag mapping.

| Requested tag | Meaning | Example result state |
|---|---|---|
| `extract=text` | Extract textual content | `applied` |
| `extract=layout` | Preserve reading order and coordinates | `applied` |
| `extract=tables` | Detect and structure tables | `applied` / `partial` |
| `extract=images` | Extract pictures and coordinates | `applied` |
| `ocr=include` | OCR where required | `applied` / `not-applicable` |
| `extract=formulas` | Extract mathematical formulas | `applied` / `unsupported` |
| `image-description=include` | Generate descriptions for relevant pictures/charts | `applied` |
| `sanitize=true` | Request sensitive-data sanitization | `applied` |
| `use-struct-tree=true` | Prefer native PDF structure tree where present | `applied` / `not-applicable` |

OpenDataLoader documents `format=json,text,html,pdf,markdown,tagged-pdf`, structure-tree processing, reading-order configuration, table methods, page selection, image output, sanitization and hybrid processing options. citeturn0search2

---

# 13. PDF Tags vs PDF Semantic Elements

Two concepts must not be confused.

### Processor/result tags

These are Synanton extraction feature states:

```text
feature.ocr=applied
feature.tables=applied
```

### PDF semantic elements

These are extracted structures:

```text
heading
paragraph
table
list
image
caption
formula
```

OpenDataLoader supports semantic element extraction with bounding boxes and also supports native Tagged PDF structure trees. citeturn0search0turn0search3

The Synanton result should preserve both:

```text
features
+
elements
```

---

# 14. Plain Text Extraction

Plain text is the simplest document modality.

Input:

```text
notes/meeting-2026-08-24.txt
```

Result:

```json
{
  "type": "document",
  "mediaType": "text/plain",
  "metadata": {
    "charset": "UTF-8"
  },
  "elements": [
    {
      "id": "text-1",
      "type": "text",
      "content": "The extraction cluster will be tested next week."
    }
  ],
  "flattenedText": "The extraction cluster will be tested next week.",
  "features": {
    "text": "applied",
    "layout": "not-applicable"
  }
}
```

No PDF-specific coordinates should be invented.

---

# 15. Ebook / EPUB Extraction

An ebook has a logical document hierarchy that should be preserved.

Example:

```text
book
  metadata
  chapter
    heading
    paragraph
    quote
    list
    image
```

Example result:

```json
{
  "type": "document",
  "mediaType": "application/epub+zip",
  "metadata": {
    "title": "Enterprise Architecture",
    "author": "Example Author"
  },
  "elements": [
    {
      "id": "chapter-01",
      "type": "chapter",
      "order": 1,
      "content": [
        {
          "type": "heading",
          "level": 1,
          "text": "Architecture Boundaries"
        },
        {
          "type": "paragraph",
          "text": "Boundaries define which concerns can evolve independently."
        }
      ]
    }
  ],
  "flattenedText": "Architecture Boundaries\nBoundaries define which concerns can evolve independently."
}
```

EPUB extraction SHOULD preserve the original reading order and chapter identity.

---

# 16. HTML Extraction

HTML extraction should preserve semantic structure while filtering presentation and navigation noise where appropriate.

Example:

```json
{
  "type": "document",
  "mediaType": "text/html",
  "metadata": {
    "title": "Extraction Architecture",
    "canonicalUrl": "https://example.invalid/article"
  },
  "elements": [
    {
      "type": "heading",
      "level": 1,
      "content": "Extraction Architecture"
    },
    {
      "type": "paragraph",
      "content": "The extraction plane is a platform boundary."
    },
    {
      "type": "list",
      "items": [
        "Documents",
        "Audio",
        "Images",
        "Video"
      ]
    }
  ]
}
```

HTML-specific fields such as DOM selectors or source URLs MAY be retained as provenance, but they should not leak into generic knowledge structures unless useful.

---

# 17. Audio Conversation Extraction

Audio is fundamentally temporal.

A plain transcript is insufficient because it loses conversational structure.

The extraction result SHOULD preserve:

```text
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

```json
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
  ]
}
```

---

# 18. Pauses

Pauses are useful conversational structure.

The extraction plane SHOULD distinguish:

```text
pauseBefore
pauseAfter
silenceInterval
```

Example:

```json
{
  "type": "pause",
  "startMs": 8120,
  "endMs": 10250,
  "durationMs": 2130
}
```

A pause is an observable audio property.

The extraction plane SHOULD NOT interpret it as:

```text
speaker was uncertain
speaker disagreed
speaker was thinking
```

Those are semantic interpretations and belong downstream.

---

# 19. Simultaneous Talk / Overlap

Conversation extraction MUST NOT force overlapping speech into a single linear sequence.

Example:

```text
Speaker A:  10.200s ---------------- 13.800s
Speaker B:             12.900s ---------------- 15.200s
                           | overlap |
```

Represent it explicitly:

```json
{
  "id": "u021",
  "speakerId": "speaker-1",
  "startMs": 10200,
  "endMs": 13800,
  "text": "The important part is-"
},
{
  "id": "u022",
  "speakerId": "speaker-2",
  "startMs": 12900,
  "endMs": 15200,
  "text": "Yes, but the contract-"
}
```

The overlap relationship can be represented independently:

```json
{
  "type": "overlap",
  "source": "u021",
  "target": "u022",
  "startMs": 12900,
  "endMs": 13800
}
```

This preserves evidence that both speakers were talking simultaneously.

---

# 20. Conversation Summarization

Summarization is an enrichment stage.

The transcript remains authoritative extraction evidence.

```text
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

```json
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

---

# 21. Audio Feature Tags

Recommended request tags:

```text
audio=transcription
audio=diarization
audio=pauses
audio=overlap
audio=summary
language=en
```

Result states might be:

```json
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

---

# 22. Image Extraction

Image processing should separate deterministic extraction from LLM/VLM interpretation.

```text
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

```json
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
  }
}
```

---

# 23. OCR vs Image Description

OCR answers:

> **What text is visibly present?**

Image description answers:

> **What is visually depicted?**

They are complementary.

Example:

```text
Image
 |
 +--> OCR
 |     "Q3 Revenue: €4.2M"
 |
 +--> VLM
       "A business presentation slide showing quarterly revenue..."
```

The two outputs should remain separate so downstream consumers can decide how much trust to place in each.

---

# 24. Image Description Provenance

LLM/VLM-generated descriptions MUST carry provenance.

Recommended fields:

```json
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

---

# 25. Video Extraction

Video should be treated as a timeline containing multiple synchronized modalities.

```text
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

---

# 26. Video Scene Model

Example:

```json
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
  ]
}
```

A scene is an extraction boundary, not a business event.

---

# 27. Short-Clip Extraction

The video plane should support bounded clips rather than requiring the entire video to be interpreted by an LLM.

Example request:

```json
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

```json
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
  "summary": "The presenter introduces the structured extraction plane and its processing boundary."
}
```

This bounded model controls LLM/VLM cost and gives downstream consumers precise source references.

---

# 28. Cross-Modality Result Model

All modalities should share a common envelope.

```json
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

  "flattenedText": null,

  "provenance": {}
}
```

Only `payload` changes shape by modality.

---

# 29. Modality Payloads

```text
StructuredPayload
 |
 +-- DocumentPayload
 |     |
 |     +-- elements
 |     +-- metadata
 |     +-- pages
 |
 +-- ConversationPayload
 |     |
 |     +-- participants
 |     +-- utterances
 |     +-- pauses
 |     +-- overlaps
 |     +-- summaries
 |
 +-- ImagePayload
 |     |
 |     +-- metadata
 |     +-- OCR regions
 |     +-- descriptions
 |
 +-- VideoPayload
       |
       +-- timeline
       +-- scenes
       +-- keyframes
       +-- transcript
       +-- OCR
       +-- clip analyses
```

This keeps the common contract stable while allowing modality-specific evolution.

---

# 30. Provenance Model

Every extracted or generated element should be traceable back to its source location where possible.

## Documents

```text
source
  -> page
  -> bounding box
  -> element
```

## Audio

```text
source
  -> start/end timestamp
  -> utterance
```

## Images

```text
source
  -> bounding box
  -> OCR/description
```

## Video

```text
source
  -> time interval
  -> frame
  -> visual/audio extraction
```

A common source locator can therefore be:

```json
{
  "kind": "page",
  "page": 4,
  "bbox": [100, 200, 400, 300]
}
```

or:

```json
{
  "kind": "time",
  "startMs": 125000,
  "endMs": 145000
}
```

---

# 31. Extraction vs Knowledge Processing

The boundary should remain explicit.

### Extraction

```text
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

### Knowledge processing

```text
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
```

The knowledge layer can reject, enrich, resolve, or combine extracted observations.

---

# 32. End-to-End Example - PDF

```text
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
        Knowledge Processing
                |
                v
        Search / Relationships
        / Ontology / Business Logic
```

---

# 33. End-to-End Example - Audio Meeting

```text
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
              v
       Knowledge Processing
```

---

# 34. End-to-End Example - Image

```text
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
Knowledge Processing
```

---

# 35. End-to-End Example - Video Clip

```text
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
                    v
             Knowledge Processing
```

---

# 36. Adapter Architecture

The extraction plane should use adapters to isolate implementation-specific processors.

```text
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

```text
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

---

# 37. OpenDataLoader as a PoC Adapter

The PDF PoC should therefore be:

```text
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
```

This design means OpenDataLoader can later be replaced by:

```text
another PDF parser
Synanton-native PDF processor
specialized OCR pipeline
external document extraction service
```

without changing the Synanton extraction API.

---

# 38. PDF PoC Feature Matrix

The first PoC should validate:

| Feature | PoC | Result |
|---|---:|---|
| Text extraction | Yes | structured text |
| Reading order | Yes | ordered elements |
| Bounding boxes | Yes | source coordinates |
| Headings | Yes | heading hierarchy |
| Lists | Yes | list structure |
| Tables | Yes | structured tables |
| Images | Yes | image references/coordinates |
| OCR | Yes | OCR text for scanned pages |
| Formula extraction | Yes | LaTeX |
| Image/chart description | Yes | generated description |
| Tagged PDF structure | Optional | native semantic structure |
| Sanitization | Optional | redacted output |
| Markdown | Yes | RAG-friendly projection |
| HTML | Optional | display projection |
| Annotated PDF | Optional | visual debugging |

OpenDataLoader documents these capabilities and its current output formats; exact support can vary by deterministic versus hybrid mode. citeturn0search0turn0search2

---

# 39. PoC Acceptance Criteria

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

---

# 40. Recommended Canonical Example

The following should be used as the first end-to-end contract example.

### Request

```json
{
  "contentRefId": "content-pdf-001",
  "mediaType": "application/pdf",
  "tags": {
    "source-system": "local-library",
    "document-class": "technical-book",
    "extract": "text,layout,tables,images,formulas",
    "ocr": "include",
    "image-description": "include"
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

```json
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
    "image-description": "applied"
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

  "flattenedText": "Structured Content Extraction Plane\nThe extraction plane converts raw enterprise content into structured representations.\nFeature Purpose\nOCR Extract text from scanned pages\nLayout Preserve document reading order\nTables Preserve tabular structure",

  "provenance": {
    "processor": "opendataloader-pdf",
    "processorVersion": "2.5.0",
    "generatedAt": "2026-08-24T16:00:00Z"
  }
}
```

The `2.5.0` processor version above is an example of recording processor provenance; the OpenDataLoader project currently lists v2.5.0 as its latest release in the GitHub releases page. citeturn0search5

---

# 41. Implementation Boundary

The implementation should be split into three layers.

```text
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

This separation is the key design decision.

---

# 42. What the Extraction Plane Should Not Do

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

```text
OCR:
"Acme Corporation"
```

is extraction.

Resolving:

```text
"Acme Corporation"
       |
       v
Company#1234
```

is knowledge processing/entity resolution.

Likewise:

```text
transcript:
"We should delay the deployment."
```

is extraction.

Interpreting this as:

```text
decision = deployment-delay
```

is downstream semantic processing.

---

# 43. Relationship to Lucentrix

The extraction plane consumes content made available by the ingestion/source layer.

A representative Synanton flow is:

```text
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
                 Knowledge Platform
```

Lucentrix knows **how to retrieve the source content**.

The extraction plane knows **how to turn retrieved content into structured content**.

The knowledge platform knows **how to interpret and use that structured content**.

---

# 44. Local Books Library Example

For a local ebook/PDF library:

```text
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

```text
content-001 -> /books/architecture/clean-architecture.pdf
content-002 -> /books/architecture/domain-driven-design.epub
...
```

The extraction plane processes each reference according to media type:

```text
PDF  -> PDF adapter
EPUB -> EPUB adapter
```

The result becomes normalized structured content while preserving source provenance.

---

# 45. Operational Sequence

A typical asynchronous flow is:

```text
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
12. Operation marked COMPLETED/PARTIAL/FAILED
          |
13. Knowledge processing consumes result
```

---

# 46. Key Design Decision

The central design decision is:

> **Use OpenDataLoader PDF as a replaceable PDF processor for the PoC, while making Synanton's Structured Content Extraction Plane the stable architectural boundary.**

The same principle should apply to all other modalities.

```text
PDF       -> OpenDataLoader adapter
Audio     -> ASR/diarization adapter
Image     -> OCR/VLM adapter
Video     -> media/scene/audio/image adapters
```

No processor should become the API.

---

# 47. Future Evolution

The extraction plane can evolve independently.

Examples:

```text
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

```text
request
  -> operation
  -> status
  -> structured payload
```

The implementation behind it can change.

---

# 48. Decision Summary

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
- video scene and short-clip extraction.

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
- coupling extraction to a specific scheduler or hardware pool.

---

# 49. References

1. Synanton v1.21 Structured Content Extraction Plane proposal supplied as the basis for this design.
2. OpenDataLoader PDF repository and documentation. urlopendataloader-project/opendataloader-pdfhttps://github.com/opendataloader-project/opendataloader-pdf
3. OpenDataLoader PDF JSON schema. urlOpenDataLoader PDF JSON schemahttps://github.com/opendataloader-project/opendataloader-pdf/blob/main/schema.json
4. OpenDataLoader PDF options. urlOpenDataLoader PDF optionshttps://github.com/opendataloader-project/opendataloader-pdf/blob/main/options.json
5. OpenDataLoader Java examples. urlOpenDataLoader PDF exampleshttps://github.com/opendataloader-project/opendataloader-pdf-examples

