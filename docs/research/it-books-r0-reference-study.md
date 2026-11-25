# IT Books R0 — Reference study and implementation decision record

**Issue:** #900 · **Parent:** #893 / #892
**Status:** draft for review. No Docling runtime, models, or new libraries are adopted by this document.
**Scope:** study Docling/Docling Graph as reference implementations and decide which techniques Synanton should implement independently.

## 1. Decision summary

Synanton should adopt the **architectural patterns** of ontology-driven extraction and deterministic provenance binding, while implementing them with Synanton-owned contracts. Docling is a reference, not a dependency or extraction backend.

The governing principle:

> Synanton owns the implementation, contracts, semantics, and lifecycle. Docling informs the research; it does not become a Synanton dependency.

Rejected as Synanton contracts/runtime:
- Docling as a runtime dependency or extraction backend.
- Docling Pydantic models/templates as authoritative extraction contracts.
- Docling Graph as the graph persistence model.
- Docling's provenance ledger as an authoritative evidence store.
- Docling-specific `self_ref`/`__provenance__` formats.

## 2. Technique decision table (R0.1)

Cost/value are planning estimates to be revised by implementation evidence.

| Technique | Initial decision | Synanton contract impact | Cost | Value | Risk if omitted |
|---|---|---|---|---|---|
| Ontology → SPEC → template | Adopt | Extraction-contract compiler; digest identity | S | High | Ontology semantics stay implicit; no reproducible extraction contract |
| Direct structured extraction | Adapt | Existing extraction adapter contract | S | High | No simple baseline for structured documents |
| Skeleton-then-flesh | Adapt into RAKG | Staged extraction state and candidate representation | M | High | Harder to recover dense documents without losing evidence |
| Deterministic provenance binding | Adopt | Evidence/source-location contract | M | High | Evidence cannot be verified or compared across backends |
| Layout-aware processing | Adapt via selected libraries | Structural representation (R0.2) | L | High | Chunks ignore document structure; weak offsets/bboxes |
| PDF table/figure reconstruction | Defer in P1 | Explicit deferred scope; no fabricated table/figure elements | M | Low for v1 | P4 must not treat absent structure as a regression |
| Bounding-box grounding | Adopt selectively | Source-location geometry + explicit unavailable state | M | Medium–High | Spatial evidence cannot be measured; silent degradation risk |
| Candidate graph fusion | Defer pending evaluation | Candidate preprocessing only | M | Uncertain | Possible duplicate candidates across documents; not required for per-document v1 |
| Multi-format conversion | Adapt via selected libraries | Format-adapter boundary | L | Medium | Narrow format support; avoid writing parsers from scratch |

Not adopted: Docling runtime, Docling Graph persistence/provenance contracts, LLM gap-fill in the deterministic compiler path.

## 3. Structural representation contract (R0.2)

This is the interface between document processing (P1) and evidence binding (P3). It is Synanton-owned and parser-neutral.

```text
DocumentStructure {
  tenantId
  sourceId
  sourceVersionId
  documentId
  mediaType
  normalizationVersion
  pages[]         // pageNumber, width, height, coordinateSystem, unit
  elements[]      // see below
  chunks[]        // chunkId, elementIds[], readingOrder, offsets
}

Element {
  elementId
  pageNumber?     // nullable where inapplicable
  elementType     // HEADING, PARAGRAPH, TABLE, FIGURE, LIST, CODE, OTHER
  readingOrder
  chunkId
  chunkStartOffset / chunkEndOffset
  documentStartOffset / documentEndOffset
  boundingBox?    // x0,y0,x1,y1 in page coordinate system
  geometryStatus  // AVAILABLE | UNAVAILABLE | NOT_APPLICABLE
  text?
  parserName
  parserVersion
}
```

Rules:
- Offsets use a documented Unicode code-point/character coordinate system and the named normalization contract; do not mix byte and character offsets.
- Bounding boxes name their page coordinate system, origin, and units.
- `UNAVAILABLE` is an explicit state, never silently replaced by document-scope evidence.
- A `Mention` is not an element, entity, or canonical node. Mentions remain source spans with source identity, even when they resolve to one canonical entity.
- Multiple mentions may resolve to one canonical entity; all source evidence remains attached to mentions.

## 4. Digest and reproducibility rules (R0.3)

| Identity | Inputs |
|---|---|
| Ontology release | Existing #868 `releaseId` + `releaseDigest` |
| Compiler | `ontologyReleaseDigest + compilerVersion + exportProfile + schemaVersion` |
| Extraction experiment | `provider + modelRevision + configurationDigest + templateDigest + documentVersion` |
| Adapter output | `candidateRepresentationVersion + evidenceContractVersion + canonicalizedOutput` |

Rules:
- SHA-256 over a canonical serialization; document the serialization (sorted keys, LF, UTF-8, excluded metadata).
- Exclude timestamps, hostnames, absolute paths, and other nondeterministic metadata.
- Identical compiler inputs must produce identical SPEC/template digests.
- Identical extraction inputs identify the same experiment configuration; they do **not** guarantee identical LLM outputs. Extraction reproducibility is measured, not assumed.

## 5. Determinism scope (R0.4)

| Layer | Guarantee |
|---|---|
| Compiler | No LLM in the compile path; identical pinned inputs produce identical SPEC/template digests |
| Extraction | LLM use is expected; provider/model/config/template/document are pinned; output reproducibility is measured |
| Provenance binding | Deterministic post-extraction processing; no additional LLM calls |

Conflating these will create false acceptance failures.

## 6. Library shortlist (R0.5)

No dependency is selected by R0. P1 must validate the choice.

| Need | Candidates | Trade-offs |
|---|---|---|
| PDF text/coordinates | Apache PDFBox 3.x; existing OpenDataLoader PDF path | PDFBox gives direct character/geometry control; OpenDataLoader is already used by content extraction. |
| Format detection/text | Existing Apache Tika 2.9.2 | Already in the platform; weak layout/bbox story, useful for format breadth. |
| Markdown/HTML structure | Existing HTML path; a small Markdown parser candidate | Must preserve headings/reading order and offsets; avoid a second structural model. |
| Office documents | Tika parsers | Conversion quality varies; bbox often unavailable; must report `UNAVAILABLE`. |
| OCR | Deferred | Not required for IT Books v1; introduces model/runtime dependency. |
| Layout/reading order | Synanton-owned heuristics over selected parser output | Avoid Docling runtime; start with headings/paragraphs/tables and explicit limitations. |

Recommended starting scope: **PDF + Markdown**, with explicit unavailable-bbox states and parser provenance. Defer OCR and advanced table extraction.

## 7. P2 go/no-go (R0.6)

P2 is **not** on the IT Books v1 critical path.

Proceed only if the A2 fixture demonstrates:
- multiple documents producing overlapping candidate structures;
- fusion materially improves consolidation or evidence aggregation;
- audit records preserve original candidate identities and evidence;
- benefits are measurable independently of canonical entity resolution.

Otherwise defer P2. Cross-book provenance can be demonstrated with per-document candidate graphs. Fusion never assigns canonical identity, crosses tenants, bypasses resolution/validation, or promotes.

## 8. P1/P3 coupling

P3 cannot measure bounding-box preservation or evidence misbinding until P1 provides stable source coordinates. P1 must establish R0.2 before P3 evaluation. P3a unit tests bind against R0.2; P3b/P4 run end-to-end against A2’s independent fixture.

## 9. Sign-offs

| Decision | Responsible owner |
|---|---|
| Reference findings / technique selection | Synanton architecture owner |
| Parsing-library selection | Document-processing implementation owner |
| Structural representation contract | Platform/data architecture owner |
| Ontology compiler semantics | Syntology owner |
| Evidence contract and binding | Platform/provenance owner |
| Fixture and acceptance | IT Books workstream owner |

One person may hold multiple roles; each decision requires explicit sign-off.

## 10. Boundaries

- No Docling runtime/model dependency, no Docling Pydantic/graph/provenance contracts.
- No direct vLLM/TEI calls; GPU-backed inference uses `synanton.gpu.v1`.
- No A4–A8 issues; no A2/A3 blocking.
- No F1/gold/gate/profile/ADR-044 change.
