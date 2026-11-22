# Synanton Design 1.36 Proposal: Ontology-Grounded, Budget-Bounded LLM Extraction (OGCE)

**Status:** Draft for review — 2026-11-21. Not an accepted design.
The `v1.36` slot is provisional; this document is a technical proposal, not an
implementation commitment.

**Scope:** How Synanton should combine text chunks, the active ontology, and LLM
extraction without the current JSON-schema fragility and chunk-fan-out problems.

**Related decisions and plans:**

- [ADR-022 Pluggable Knowledge Extraction Pipeline](../../decisions/adr-022-extraction-engine-architecture.md)
- [RAKG Phase 2 asynchronous extraction consumer](../../../planning/extraction-consumer-phase2.md)
- [Extracted-mention graph contract](../../../planning/extracted-mention-graph-contract.md)
- [Extraction gold schema](../../../planning/extraction-gold-schema.md)
- [v1.21 Structured Content Extraction Plane](../v1.21/Synanton_v1.21_Structured_content_extraction_plane.md)
- [v1.22 Semantic Chunking](../v1.22/Synanton%20v1.22%20%20Structured%20Content%20Semantic%20Chunking%20Design%20Proposal.md)
- [Domain Ontologies and Cross-Domain Semantic Mapping v0.3](../ontology-domain-mapping-v0.3.md)
- ADR-030 concept identity, ADR-039 ontology registry contracts (both currently Proposed)

**External comparison:** TrustGraph `2.10`-era technical specifications in
`/home/aminin/workspace/graph/trustgraph/docs/tech-specs/` and the TrustGraph
README (OntologyRAG). References are listed in Appendix D.

---

## 1. Executive summary

Synanton already has the harder half of the problem: structure-aware chunks with
page/section provenance, an asynchronous extraction consumer, an ontology service
(Syntology) with TBox/SHACL validation, deterministic mention identities, and a
frozen extraction gold. What it lacks is a **contract between the ontology and
the LLM call**:

1. The LLM is shown a hand-written prose "schema" with duplicated type lists and
   no machine validation. Output is a single monolithic JSON object; a truncated
   response loses everything, and the parser silently defaults malformed fields.
2. Every 512-token retrieval chunk is sent to the LLM, then a document-level
   "Pass 2" concatenates every Pass-1 response into one prompt. Prompt size and
   cost grow with chunk count, while the output budget stays fixed. That is the
   "chunk flood": too many calls, then one unbounded aggregation call.
3. The ontology is only an allowed-type hint. There is no per-window subset, no
   property/domain/range information in the prompt, and no schema derived from
   the pinned ontology release.

TrustGraph solves the schema and ontology-context parts well:

- it embeds ontology elements, retrieves a small relevant subset per chunk, and
  closes over parents/domain/range/properties;
- it emits **JSONL**, one validated object per line, so truncation loses at most
  one line;
- it makes the LLM return natural names plus ontology identifiers, while code
  owns URI construction and emits labels;
- it validates extracted triples against class/property existence and
  domain/range.

TrustGraph does not solve chunk fan-out: it still performs one LLM call per
chunk, and it does no document-level consolidation. Synanton should adopt
TrustGraph's ontology-selection and JSONL ideas, but not its chunker, and should
add extraction windows, budgets, consolidation, and quarantine on top.

**Recommendation:** implement OGCE in four increments:

1. **P0 — schema safety now.** JSONL output, per-line JSON Schema, truncation
   resilience, quarantine, and the legacy Pass-2 cache fix.
2. **P1 — ontology contract.** Syntology compiles the pinned ontology release
   into an extraction schema (classes, aliases, properties, domain/range) with a
   digest; prompts are generated from it, not hand-maintained.
3. **P2 — bounded fan-out.** Decouple retrieval chunks from extraction windows;
   batch windows under a per-document budget; replace the monolithic Pass-2 call
   with deterministic consolidation; add backpressure and coverage reporting.
4. **P3 — ontology subsetting and full validation.** Lexical + embedding subset
   selection, dependency closure, batch semantic validation, and promotion-time
   SHACL.

---

## 2. Current state and failure modes

### 2.1 Two extraction paths exist today

| Path | Where | Shape |
|---|---|---|
| Legacy synflux enrichment | `java/synflux/.../pipeline/stage/EnrichStage.java` | Pass 1: one LLM call per chunk (`maxTokens=300`); Pass 2: one call over all Pass-1 outputs (`maxTokens=800`). |
| RAKG async consumer | `java/synflux/.../extraction/ExtractionConsumer.java` + `java/extraction-engine/` | One `ExtractionEngineAdapter.extract()` call per stored chunk, sequentially; persists candidates in `extraction_results`; graph commit validates relations. |

The legacy path is the one with the chunk-flood aggregation. The RAKG path is the
strategic path (ADR-022) but inherits the same per-chunk fan-out and monolithic
JSON contract. The proposal targets the RAKG path as canonical and retires the
legacy Pass-2 path.

**Relationship to the architecture baseline.** Design 1.22
(`docs/architecture/synanton-design-1.22.md` §5b/5c) still describes Pass 1 and
Pass 2 as inline ingestion sub-stages. ADR-022 and
`docs/planning/extraction-consumer-phase2.md` later moved LLM extraction
out-of-process and asynchronous. This proposal follows the ADR/planning state,
not the stale 1.22 description, and narrows 1.22 §5b/5c to "candidate generation
under an ontology contract". Design 1.22 Part X (semantic chunking) is unchanged:
retrieval chunks stay structure-aware; only the extraction unit changes. The
Syntology ontology-management document
(`docs/architecture/syntology/ontology-management.md`) describes the v1.17
demo-scoped module; the compiler proposed here is an additive capability of that
module, not a redesign of its HCL/versioning model.

### 2.2 JSON-schema fragility

Concrete defects:

- **Prompt-only schema.** `extraction-v1.txt` ends with
  `Return ONLY valid JSON with this exact structure: {...}`. Nothing validates
  that structure. `LlmExtractionAdapter` parses with a custom tolerant reader
  and then reads fields defensively (`Json.string(..., fallback)`).
- **Monolithic object.** One `{ "preEntities": [...], "relations": [...] }`
  document means a token-limit truncation loses every entity and relation. The
  adapter treats an incomplete/truncated object as a failure; the consumer marks
  `LlmIncompleteOutputException` terminal.
- **No provider structured output.** `CompletionRequest` carries only
  `model`, `systemPrompt`, `userMessage`, `temperature`, `maxTokens`. There is no
  `response_format`/JSON-schema mode and no repair pass.
- **Pass-2 parser is less tolerant than it looks.**
  `Pass2AnalysisParser` calls `ObjectMapper.readTree()` on the raw response. A
  Markdown fence or a leading sentence fails the whole parse and returns empty
  lists. It then defaults missing `type` to `"Unknown"` and missing `confidence`
  to `1.0`, which hides model errors as high-confidence facts.
- **Prompt drift.** `pass1-chunk-summary.mustache` and
  `pass2-document-entities.mustache` exist in resources but `EnrichStage` builds
  different inline prompt strings. The type vocabulary is duplicated in the
  prompt, `LlmExtractionAdapter.DEFAULT_TYPES`, `ExtractionContext`, and the
  gold schema.
- **Caching bug.** In `EnrichStage`, a Pass-2 cache hit executes
  `cached.get().analysisJson();` without assigning or using the result. The
  method then logs completion. Any later reader depends on the cache row, so the
  bug is latent rather than loud, but it is still incorrect control flow.
- **Provenance loss in Pass 2.** `Pass2AnalysisParser` reads `chunk_ordinals`,
  but the Pass-2 prompt never asks for them. Document-level results therefore
  cannot point back to the chunks that supported each entity.

### 2.3 Chunk flood

Define the problem precisely; it is two separate failures:

1. **Call fan-out.** A 50-page document is roughly 200 retrieval chunks
   (`extraction-consumer-phase2.md`). At 1–2 s per call, one document is 3–7
   minutes of serial GPU time. `ExtractionConsumer` currently loops chunks one at
   a time (`for (ChunkRow chunk : chunks)`), and the legacy path uses a
   per-document `Executors.newFixedThreadPool(parallelism)` with no global
   admission control.
2. **Aggregation flood.** Legacy Pass 2 joins every Pass-1 response with
   `String.join("\n---\n", pass1Results)` and sends that single prompt. Prompt
   size grows O(chunks), the response budget is fixed at 800 tokens, and failed
   chunks contribute `"{}"`. Large documents either saturate the output budget,
   time out, or produce an entity list biased toward the early chunks.

The chunker itself is not the defect. `SemanticChunker` is deliberately
structure-aware: 512-token default, tables atomic, lists atomic, section path and
page provenance, partial-section marking, fallback only when structure is
missing. That is better for retrieval and citation than TrustGraph's
character-based splitter. Changing it would trade away quality without fixing
extraction cost.

The defect is that **retrieval granularity is being reused as extraction
granularity**, and document-level aggregation is unbounded.

### 2.4 Ontology is not actually in the loop

Today the extraction contract is:

```java
record ExtractionContext(
    String tenantId,
    String documentId,
    Set<String> allowedEntityTypes,
    String sourceVersionId) {}
```

That is a flat type list. It does not carry:

- the ontology release/version (so cache keys and results are not pinned);
- class descriptions, aliases, or parent classes;
- object/datatype properties, domain/range, cardinality, or SHACL constraints;
- a stable class identifier distinct from a display label.

The relation vocabulary is compiled into
`LlmExtractionAdapter.DEFAULT_PREDICATES` and the relation contract string. The
model is asked to invent natural-language predicates and then code normalizes
them. Syntology's `validate-relation` endpoint exists and GraphWriter uses it at
commit time, but the model never sees the constraints it will later be judged
against. That produces avoidable rejections and makes the extraction prompt
dependent on code changes rather than ontology releases.

---

## 3. Comparison: Synanton vs TrustGraph

### 3.1 Ontology and extraction

| Dimension | Synanton today | TrustGraph | Assessment |
|---|---|---|---|
| Ontology artifact | HCL schema → IR → SHACL/TBox in Jena; registry/versioning direction in v0.3 and ADR-030/039 | OWL-inspired JSON: `metadata`, `classes`, `objectProperties`, `datatypeProperties`, URIs, labels, comments, domain/range, cardinality | Synanton governance is stronger; TrustGraph's extraction-facing shape is more usable by an LLM. |
| Role of ontology in extraction | Flat allowed-type list; predicates hardcoded; validation mostly after extraction | Ontology drives extraction: subset selection, enum identifiers, domain/range checks | Adopt ontology-driven extraction without importing OWL reasoning. |
| Ontology selection | None; one fixed vocabulary for all chunks | Per-sentence embedding search, top-k (10), threshold (0.3), then dependency closure (parents, domains, ranges, incident properties) | Adopt, but cache and add lexical matching. |
| Ontology version pinning | Strong direction, but extraction context does not carry a release | Ontology is a config item; collection/flow scoped | Synanton should pin release + schema digest in every extraction cache key and result. |
| Type identity shown to LLM | Display strings such as `Organization` | Prefixed identifiers such as `fo/Recipe` plus labels | Use short stable IDs from the compiled subset, never free-form labels as keys. |

### 3.2 Chunking and extraction unit

| Dimension | Synanton today | TrustGraph | Assessment |
|---|---|---|---|
| Retrieval chunking | Structure-aware 512 tokens; table/list atomic; sectionPath/pages; fallback | Recursive character splitter, default 2000 chars / 100 overlap | Keep Synanton's chunker. |
| Internal text unit for ontology matching | Whole chunk | Sentence split (NLTK) | Adopt sentence-level matching inside a window. |
| Extraction unit | One retrieval chunk per LLM call | One retrieval chunk per LLM call | Both fan out. Synanton must add extraction windows and budgets. |
| Document aggregation | Legacy Pass 2: all chunk outputs in one prompt | None | Replace with bounded deterministic consolidation. Do not copy either extreme. |
| Large-document handling | Async consumer, sequential per chunk | Streaming/chunked upload; still per chunk extraction | Add per-document window budget, not just upload streaming. |

### 3.3 Output contract, validation, provenance

| Dimension | Synanton today | TrustGraph | Assessment |
|---|---|---|---|
| Output format | Monolithic JSON object (or legacy Pass-1 JSON) | JSONL: one JSON object per line; `type` discriminator for mixed outputs | Adopt JSONL. |
| Schema enforcement | Prose only; custom tolerant parser; defaults hide errors | Per-line JSON Schema, including `oneOf` discriminated unions; invalid lines skipped | Adopt per-line schema + quarantine. |
| Truncation behavior | Whole response fails | Partial results survive; truncated final line discarded | Adopt. |
| URI/ID construction | Code normalizes type strings; mention IDs deterministic downstream | LLM returns names/type IDs; code normalizes names, builds URIs, emits `rdf:type`/`rdfs:label` | Synanton already does most of this; make the LLM emit ontology IDs, not invented predicates. |
| Semantic validation | `validate-relation` at graph commit (TBox + predicate SHACL) | Class/property existence, domain/range incl. subclass, cardinality, datatype; rejects invalid triples | Keep Synanton's commit validation; add lightweight per-line validation and batch semantic validation. |
| Dedup | Relix canonicalizer + per-document local map; Pass-2 can duplicate | Entity registry during extraction | Add per-window registry and deterministic merge; keep canonical resolution downstream. |
| Provenance | Strong: chunk, page, section, offsets, evidence spans, mention graph contract | PROV-O document → page → chunk → edge; named graphs; retrieval traces | Synanton is competitive; preserve per-line provenance through consolidation. |
| Fan-out control | No global budget; sequential or per-doc pool | Bounded prompts but no per-document budget | Synanton must go beyond TrustGraph here. |

### 3.4 What to adopt, and what not to adopt

**Adopt:**

- JSONL + per-line JSON Schema + discriminated `type`.
- Natural-language surface forms plus ontology identifiers; code owns IRIs.
- Ontology subset retrieval with dependency closure and a small-ontology bypass.
- Parse-time structural validation and explicit rejection/quarantine.
- Provenance from source span to extracted candidate.

**Do not adopt:**

- TrustGraph's character-window chunker; Synanton's semantic chunker is better
  for retrieval and citation.
- Full OWL reasoning in the extraction path. Synanton needs a profile, not a
  reasoner, at extraction time.
- Per-chunk ontology embedding without caching; that moves the cost rather than
  removing it.
- TrustGraph's lack of document-level consolidation. Synanton needs bounded
  consolidation, not a single giant Pass 2.
- Treating ontology config as unversioned. Synanton's registry direction is
  better and should be kept.

**Synanton already better than TrustGraph:**

- Ontology governance, release/version direction, tenant scope, canonical graph
  identity, and ADR discipline.
- Structure-aware chunking with `sectionPath`, page spans, and atomic tables.
- Deterministic mention/relation IDs and a persisted candidate graph contract.
- A frozen extraction gold and measured baseline (entity F1 0.618, relation F1
  0.078 per ADR-022).

---

## 4. Proposed design: Ontology-Grounded Chunk Extraction (OGCE)

### 4.1 Principles

1. **Ontology is the contract.** Every extraction call pins an ontology release
   and a compiled extraction-schema digest. The prompt is generated from that
   schema.
2. **LLM proposes, code decides.** The model returns surfaces, class IDs,
   predicate IDs, values, and evidence. Code anchors spans, validates, assigns
   identity, and builds graph/RDF representations.
3. **One line is one fact.** JSONL, never a wrapper array or object. A bad or
   truncated line loses one fact, not the document.
4. **Retrieval chunks are not extraction windows.** Retrieval stays 512-token
   structure-aware. Extraction packs multiple chunks or a whole section into a
   bounded window, with explicit overlap.
5. **No prompt grows with document size.** Consolidation is bounded and
   deterministic by default; an LLM is used only for a bounded conflict/alias
   resolution call.
6. **Invalid data is quarantined, not silently dropped or defaulted.** Every
   rejected line has a reason code and a counted metric.
7. **Coverage is explicit.** If a document budget is exhausted, the extraction is
   `PARTIAL` with a coverage ratio; it never pretends to have processed
   everything.
8. **Candidate knowledge is not canonical knowledge.** Extraction writes
   `ExtractedMention`/`EXTRACTED_RELATION` candidates; canonical identity and
   mapping remain downstream (v0.3, ADR-030).

### 4.2 Components

```text
Syntology (ontology owner)
  HCL/TBox/SHACL release
        |
        v
  ExtractionSchemaCompiler  -->  ExtractionSchema (classes, aliases,
        |                          properties, domain/range, digest)
        v
  GET /ontologies/{id}/versions/{v}/extraction-schema
        |
        v
Synflux extraction consumer
  ExtractionWindowPlanner  (chunks -> bounded windows + coverage)
        |
        v
  OntologySubsetSelector  (lexical + embedding + dependency closure)
        |
        v
  ExtractionEngineAdapter (LLM; JSONL; schema-aware prompt)
        |
        v
  LineValidator  (JSON -> schema -> IDs -> evidence -> dedup)
        |
        v
  CandidateConsolidator  (deterministic merge; bounded conflict resolution)
        |
        v
  extraction_results / quarantine  -->  GraphWriter / Relix
                                              |
                                              v
                                     batch semantic validation
                                     (TBox domain/range + SHACL)
```

Ownership boundaries:

| Component | Owner | Contract |
|---|---|---|
| `ExtractionSchemaCompiler` | Syntology | Pure function of a pinned ontology release; outputs a versioned JSON artifact + digest. |
| `ExtractionWindowPlanner` | Synflux extraction consumer | Input: persisted chunks + document structure; output: windows with chunk IDs, offsets, token estimates, skip reasons. |
| `OntologySubsetSelector` | Extraction engine (or Syntology client) | Input: window text + compiled schema; output: self-contained subset + digest. |
| `ExtractionEngineAdapter` | Extraction engine | Input: `TextSegment`/window + `ExtractionContext` including schema/subset; output: JSONL lines + usage. |
| `LineValidator` | Extraction engine | Input: JSONL + schema; output: accepted candidates + quarantined lines with reason codes. |
| `CandidateConsolidator` | Synflux | Input: window results; output: document candidate set with merged evidence and provenance. |
| Semantic validation | Syntology + Relix | Existing `validate-relation` path, extended to batched entity/relation validation. |

### 4.3 Extraction windows: decouple retrieval from extraction

The planner converts persisted `ChunkRow`s into extraction windows:

- Start from the document's section/chunk order and `sectionId` where available.
- Pack consecutive chunks until `window.max-tokens` (default 2000) or a section
  boundary.
- Preserve `overlap-tokens` (default 100) between windows so relations that
  cross a boundary are visible; de-duplicate later by span.
- Never split a table chunk. A table larger than the window budget becomes its
  own window; if still too large, extract its caption/headers plus row groups,
  and mark `table_split=true`.
- Record `window_id`, `chunk_ids`, `document_offset_start/end`, and a text digest.
- Prefilter boilerplate (table of contents, index, repeated legal footer) using
  structure and lexical signals. A skipped chunk is recorded with a reason; it
  is not deleted from retrieval.
- Enforce `window.max-per-document` (default 256). Beyond the cap, switch to the
  deterministic regex/dictionary provider for the remainder and mark
  `coverage=PARTIAL`; never silently truncate.

Why windows help:

- They reduce call count by packing multiple retrieval chunks into one prompt.
- They keep prompts bounded and make output budgets predictable.
- They preserve exact per-chunk provenance because each candidate is anchored to
  a character span, and the span maps back to a chunk ordinal.
- They let the ontology subset cover the window's topics rather than one
  arbitrary 512-token slice.

### 4.4 Compile the ontology into an extraction schema

Syntology should expose a compiled artifact per immutable release (and
optionally per selected subset):

```json
{
  "schema_id": "synanton.extraction.ontology.v1",
  "ontology_id": "demo",
  "ontology_version": "1.0.0",
  "release_digest": "sha256:...",
  "schema_digest": "sha256:...",
  "namespace": "https://.../ontology/demo#",
  "classes": [
    {
      "id": "Organization",
      "iri": "https://.../ontology/demo#Organization",
      "labels": ["Organization", "Org"],
      "description": "A company, institution, or other organized group.",
      "parents": ["Agent"],
      "aliases": ["company", "firm", "institution"]
    }
  ],
  "object_properties": [
    {
      "id": "hasSupplier",
      "iri": "https://.../ontology/demo#hasSupplier",
      "labels": ["has supplier"],
      "domain": ["Product"],
      "range": ["Supplier"],
      "inverse_of": null
    }
  ],
  "datatype_properties": [
    {
      "id": "foundedYear",
      "iri": "https://.../ontology/demo#foundedYear",
      "labels": ["founded year"],
      "domain": ["Organization"],
      "range": "xsd:gYear"
    }
  ],
  "shacl_profile": "demo-extraction-v1"
}
```

Rules:

- The compiler validates that domains/ranges reference known classes/datatypes
  and that the subclass graph is acyclic; a broken release fails closed.
- IDs are stable identifiers, not display labels. Labels/aliases are prompt
  hints only.
- The artifact is immutable and addressable by digest. Extraction results record
  both `ontology_version` and `schema_digest`.
- Compilation is deterministic. The same release and compiler version produce
  the same digest.

This keeps ontology semantics in Syntology and keeps the extraction engine free
of HCL/SHACL parsing.

### 4.5 Select a small, self-contained ontology subset

For each extraction window:

1. Match lexically: class labels, aliases, and known glossary terms in the
   window. This is cheap and catches exact vocabulary that embeddings miss.
2. Match semantically: embed sentences (or window segments) and retrieve top-k
   classes/properties from a precomputed ontology embedding index. Start with
   `top_k=12`, `similarity_threshold=0.35`.
3. Close dependencies:
   - add parent classes up to the root (bounded by `max_depth`);
   - add domain and range classes for every selected property;
   - add all properties whose domain or range includes a selected class;
   - add inverse properties where declared.
4. Bound the subset: `max_classes=40`, `max_properties=80`. If the closure
   exceeds the bound, keep the highest-scoring elements plus required closure,
   and record `subset_truncated=true`.
5. Bypass: if the whole ontology has at most `bypass_class_count` (default 20)
   classes, use the full schema. The selector is an optimization, not a
   correctness requirement for small ontologies.
6. Cache the subset by
   `sha256(ontology_release|schema_digest|embedding_model|window_text_sha256)`.
7. Log misses: if the model returns a class or predicate outside the subset,
   quarantine the line with `OUT_OF_SUBSET` and increment
   `extraction_ontology_subset_miss_total`. This is the signal that the selector
   needs tuning or the subset bound is too small.

The ontology embedding index belongs in Syntology (or a shared, versioned
service), not rebuilt per extraction worker.

### 4.6 JSONL output contract

Every extraction call returns zero or more lines. No wrapper object, no array, no
Markdown fence.

```text
{"type":"entity","surface":"Acme Corporation","type_id":"Organization","char_start":14,"char_end":30,"confidence":0.91}
{"type":"entity","surface":"Beta Ltd","type_id":"Supplier"}
{"type":"relation","subject":"Acme Corporation","subject_type":"Organization","predicate":"hasSupplier","object":"Beta Ltd","object_type":"Supplier","evidence":"Acme Corporation is supplied by Beta Ltd."}
{"type":"attribute","subject":"Acme Corporation","subject_type":"Organization","predicate":"foundedYear","value":"1999","evidence":"Acme was founded in 1999."}
```

Contract notes:

- `type` is the discriminator: `entity`, `relation`, `attribute`.
- `type_id` and `predicate` must come from the compiled subset. The LLM must not
  invent identifiers.
- `char_start`/`char_end` are **hints**. Per ADR-022, the adapter anchors
  `surface` in the window text deterministically and drops unanchorable mentions.
  If omitted, the adapter searches for the surface.
- `confidence` is optional and treated as a model hint, not calibrated truth.
  Do not gate acceptance on it.
- `evidence` is required for relations and attributes and is anchored to the
  window. If the evidence quote cannot be found, fall back to the endpoint span
  union, as the current adapter does.
- Self-relations, blank surfaces, duplicate spans, and relations whose endpoints
  are missing are rejected with reason codes.

JSON Schema (Appendix B) is compiled per subset: the `type_id` and `predicate`
enums, string limits, and required fields are exact. Relation domain/range is
validated in code because JSON Schema is a poor fit for cross-field type
contracts.

### 4.7 Validation, quarantine, and consolidation

Validation order for each line:

| Step | Check | Failure code |
|---:|---|---|
| 1 | Line size and UTF-8 validity | `LINE_TOO_LARGE`, `LINE_INVALID_UTF8` |
| 2 | JSON parse (allow code fences only around the whole response) | `JSON_PARSE` |
| 3 | JSON Schema validation | `SCHEMA` |
| 4 | `type_id`/`predicate` in selected subset | `OUT_OF_SUBSET` |
| 5 | Surface/evidence anchoring in the window | `UNANCHORED` |
| 6 | Endpoint presence, no self-relation | `ENDPOINT_MISSING` |
| 7 | Window-local dedup by `(kind, surface/endpoints, span, type_id/predicate)` | `DUPLICATE` (counted, not quarantined) |
| 8 | Document consolidation dedup | `DUPLICATE` (merged evidence) |
| 9 | Batch semantic validation (TBox domain/range + predicate SHACL) | `DOMAIN_RANGE`, `SHACL` |
| 10 | Promotion/canonicalization policy | policy-specific |

Quarantine record:

```json
{
  "tenant_id": "demo",
  "content_ref_id": "...",
  "window_id": "w-0007",
  "line_number": 12,
  "code": "SCHEMA",
  "detail": "type_id must be one of the selected subset",
  "raw_line": "...",
  "ontology_version": "1.0.0",
  "schema_digest": "sha256:...",
  "model_id": "...",
  "prompt_version": "ogce-v1",
  "created_at": "..."
}
```

Consolidation:

- Default is deterministic: union accepted candidates, merge evidence spans and
  source chunk IDs, keep the highest `type_id` confidence only as a hint, and
  never merge across class IDs.
- No LLM sees the raw concatenation of all window outputs. If alias/conflict
  resolution needs an LLM, it runs over a bounded batch (e.g. 200 candidate
  labels) with its own JSON schema and its own cache key.
- Consolidation output is idempotent: rerunning it over the same window results
  produces the same document candidate set.

### 4.8 Identity and resolution boundary

- Extraction emits **candidates**, not canonical entities. This matches the
  existing extracted-mention contract.
- `mention_id` remains deterministic:
  `sha256(tenant | source_version | chunk_id | chunk_offset_start | chunk_offset_end | surface)`.
- When a window spans chunks, compute the chunk and offset from the anchored
  document span; do not assign the window ID as the chunk.
- `type_id` is an ontology class identifier, not a canonical instance ID.
- Canonical entity creation and `:RESOLVES_TO` stay downstream (Equalix /
  Syntology per ADR-030 and v0.3). Do not merge same-surface mentions across
  classes.
- Preserve the v0.3 distinction between lexical expression, concept, instance,
  candidate, and governed assertion.

### 4.9 Provenance

Every accepted candidate retains:

- window ID and prompt/schema/model digests;
- source chunk ID(s) and character offsets;
- evidence quote and its span;
- ontology release and schema digest;
- `finish_reason`, truncation flag, and whether the line came from a repair call;
- for relations, subject/object mention IDs after anchoring.

This preserves the chain
`source → chunk → window → mention candidate → relation candidate → validation →
promotion`. It also lets a model/prompt/schema regression be replayed exactly.

### 4.10 Caching, idempotency, and budgets

**Cache key** for a window result:

```text
sha256(
  tenant | ontology_release | schema_digest | prompt_version | model_id |
  embedding_model | subset_digest | window_text_sha256 | provider
)
```

Store the **validated** candidate JSONL plus quarantine counts and usage, not the
raw model response. A cache hit must be replayable without the model.

**Idempotency:** re-running a window replaces that window's candidate set; it
never appends. Document consolidation and graph commit are deterministic over
ordered window keys. This extends the existing consumer's
`(source identity, version_id)` idempotency.

**Budgets:**

| Budget | Purpose | Starting default |
|---|---|---|
| `window.max-tokens` | Bound one prompt | 2000 |
| `window.overlap-tokens` | Cross-boundary relations | 100 |
| `window.max-per-document` | Bound call count | 256 |
| `window.max-output-tokens` | Bound one response | 1024 |
| `document.max-prompt-tokens` | Bound cost | 500,000 |
| `document.max-completion-tokens` | Bound cost | 100,000 |
| `document.max-llm-calls` | Hard fan-out cap | 256 |
| `concurrency.per-tenant` | Fairness | 4 |
| `concurrency.global` | GPU protection | 16 |
| `retry.schema-repair` | Repair attempts per window | 1 |
| `retry.transport` | Exponential backoff | existing policy |

When a budget is reached, the consumer must:

1. finish and persist the windows already processed;
2. mark the document `coverage=PARTIAL` with `coverage_ratio` and a reason;
3. optionally run the deterministic regex/dictionary provider for the remainder;
4. never emit a "successful" status that implies full extraction.

**Backpressure:** one global semaphore around LLM calls, per-tenant fair queues,
and circuit breaking on provider errors. Do not create a thread pool per
document.

### 4.11 Failure handling

| Failure | Handling |
|---|---|
| Truncated response | Accept complete lines; quarantine the partial line as `TRUNCATED`; optionally one schema-repair call for the tail; mark window `partial`. |
| Invalid JSON line | Quarantine; continue with other lines. |
| Schema-valid but out-of-subset identifier | Quarantine as `OUT_OF_SUBSET`; count; do not coerce. |
| Unanchorable surface | Drop entity; drop dependent relations as `ENDPOINT_MISSING`. |
| Provider 5xx/timeout | Retry the whole window with backoff; the cache key makes retry idempotent. |
| Ontology release missing/digest mismatch | Fail closed; do not extract against a guessed ontology. |
| Semantic validation rejects relation | Keep candidate + rejection reason for analytics; do not write accepted edge. |
| Budget exhausted | `PARTIAL` with coverage; no silent success. |

---

## 5. Concrete changes in Synanton

| # | Area | Change | Files (indicative) |
|---:|---|---|---|
| C1 | Extraction output | Add JSONL mode, per-line parse, line size caps, fence handling, `TRUNCATED`/`JSON_PARSE` codes. | `java/extraction-engine/.../llm/LlmExtractionAdapter.java`, `.../json/Json.java` |
| C2 | Extraction contract | Extend `ExtractionResult`/usage with window ID, accepted lines, quarantine counts, truncation/coverage flags. | `java/extraction-engine/.../ExtractionResult.java`, `ExtractionContext.java` |
| C3 | Schema validation | Add a JSON Schema validator dependency and compile a line schema from the selected subset. | `java/extraction-engine` |
| C4 | Ontology compiler | New Syntology component and endpoint producing `ExtractionSchema` + digest from a release. | `java/syntology/.../domain/service`, `api/rest/OntologyController.java` |
| C5 | Subset selector | Lexical + embedding selector with dependency closure, bounds, cache, and miss metrics. | `java/syntology` or shared ontology client; `java/extraction-engine` |
| C6 | Window planner | Group chunks/sections into bounded windows; prefilter with recorded reasons; coverage. | New `java/synflux/.../extraction/ExtractionWindowPlanner.java` |
| C7 | Consumer | Replace sequential per-chunk loop with window batches, global/per-tenant semaphores, per-document budgets, partial status. | `java/synflux/.../extraction/ExtractionConsumer.java` |
| C8 | Consolidation | Deterministic merge of window results into document candidates; bounded optional LLM conflict resolution. | New `java/synflux/.../extraction/CandidateConsolidator.java` |
| C9 | Quarantine | Persist rejected lines with reason codes and raw line; metrics. | `java/ingestion-cache` schema + client; new table/migration |
| C10 | Legacy path | Stop using `EnrichStage` Pass 1/Pass 2 for knowledge extraction; fix the cache bug if retained for summaries; use the mustache templates or delete them. | `java/synflux/.../pipeline/stage/EnrichStage.java`, resources |
| C11 | Parser | Remove silent defaults (`type="Unknown"`, `confidence=1.0`); require schema-valid fields; keep tolerant-but-counted behavior. | `java/relix/.../loader/Pass2AnalysisParser.java` |
| C12 | Validation | Batch `validate-relation` calls; add entity class validation and promotion-time SHACL. | `java/syntology`, `java/synflux/.../extraction/graph/GraphWriter.java` |
| C13 | Provenance | Carry window/schema/ontology digests and per-line evidence through to `extraction_results` and graph commit. | `java/synflux`, `java/ingestion-cache`, `java/relix` |

Do not put ontology parsing or SHACL logic in the extraction engine. Syntology
compiles; the engine consumes an artifact.

---

## 6. Suggested configuration

All tunables belong in `application.yml` with env overrides, not Java defaults.

| Property | Default | Purpose |
|---|---:|---|
| `synflux.extraction.window.max-tokens` | 2000 | Extraction window budget. |
| `synflux.extraction.window.overlap-tokens` | 100 | Cross-window recall. |
| `synflux.extraction.window.max-per-document` | 256 | Hard fan-out cap. |
| `synflux.extraction.window.max-output-tokens` | 1024 | JSONL response budget. |
| `synflux.extraction.document.max-prompt-tokens` | 500000 | Cost cap. |
| `synflux.extraction.document.max-completion-tokens` | 100000 | Cost cap. |
| `synflux.extraction.document.max-llm-calls` | 256 | Call cap. |
| `synflux.extraction.concurrency.per-tenant` | 4 | Fairness. |
| `synflux.extraction.concurrency.global` | 16 | GPU protection. |
| `synflux.extraction.ontology.subset.top-k` | 12 | Similarity matches. |
| `synflux.extraction.ontology.subset.threshold` | 0.35 | Similarity floor. |
| `synflux.extraction.ontology.subset.max-classes` | 40 | Prompt bound. |
| `synflux.extraction.ontology.subset.max-properties` | 80 | Prompt bound. |
| `synflux.extraction.ontology.subset.bypass-class-count` | 20 | Use full schema for small ontologies. |
| `synflux.extraction.validation.max-line-bytes` | 2048 | Line cap. |
| `synflux.extraction.validation.repair-attempts` | 1 | Tail repair. |
| `synflux.extraction.prefilter.enabled` | true | Skip boilerplate. |
| `synflux.extraction.prefilter.min-confidence` | 0.8 | Skip only high-confidence boilerplate. |

Calibrate these on the demo corpus and the frozen gold; treat the table as a
starting point, not a contract.

---

## 7. Metrics and acceptance gates

### 7.1 Metrics

```text
extraction_windows_total{tenant,status}
extraction_window_skipped_total{tenant,reason}
extraction_llm_calls_total{tenant,provider,outcome}
extraction_prompt_tokens_total{tenant,provider}
extraction_completion_tokens_total{tenant,provider}
extraction_jsonl_lines_total{tenant,kind}
extraction_schema_invalid_lines_total{tenant,code}
extraction_truncated_windows_total{tenant,provider}
extraction_quarantined_lines_total{tenant,code}
extraction_ontology_subset_size{tenant}
extraction_ontology_subset_miss_total{tenant,kind}
extraction_entities_merged_total{tenant,reason}
extraction_document_coverage_ratio{tenant}
extraction_document_partial_total{tenant,reason}
extraction_validation_rejected_total{tenant,code}
```

Keep the existing `extraction_completed` structured log; add window count,
subset size, schema-valid rate, truncation count, and coverage.

### 7.2 Phase gates

**P0 — schema safety**

- No extraction path emits a monolithic JSON array/object for multi-fact output.
- A truncated response yields all complete lines and a `TRUNCATED` quarantine
  entry; a fixture proves zero complete facts are lost.
- A fenced/prefixed response parses.
- Invalid lines are counted by reason; they do not silently become `Unknown` or
  `confidence=1.0`.
- Legacy Pass-2 cache bug fixed or path retired.

**P1 — ontology contract**

- Syntology returns an `ExtractionSchema` with release + schema digests.
- The prompt contains only identifiers from the compiled schema; changing the
  ontology release changes the prompt and cache key without code changes.
- A fixture ontology with a broken domain/range fails compilation closed.

**P2 — bounded fan-out**

- A 200-chunk document never sends a prompt larger than the window budget and
  never exceeds the call budget.
- Budget exhaustion produces `PARTIAL` + coverage, not `KNOWLEDGE_READY`.
- Throughput improves versus the sequential baseline at equal GPU capacity.
- Consolidation is deterministic across reruns.

**P3 — ontology subsetting and validation**

- Subset selection reduces prompt tokens by a measured margin with no more than
  an agreed recall loss on the gold.
- Out-of-subset identifiers are quarantined and measurable.
- Domain/range + SHACL rejections at commit are reduced compared with P0.
- `validate-relation` is called in batches, not per relation.

---

## 8. Evaluation plan

Reuse the ADR-022 harness and frozen gold. Add:

1. **Schema-valid line rate** per model/provider and per window size.
2. **Truncation loss:** facts recovered from a deliberately truncated response
   versus the untruncated run.
3. **Chunk-flood curve:** prompt tokens, completion tokens, p95 latency, and
   cost per document as a function of chunk count (100, 200, 500, 1000).
4. **Coverage-quality trade-off:** full extraction versus prefiltered/budgeted
   extraction on the gold.
5. **Subset recall:** gold entities/relations whose class/predicate was absent
   from the selected subset.
6. **Dedup quality:** duplicates merged per document and false merges.
7. **Ontology conformance:** accepted relations / candidate relations before and
   after domain/range validation.
8. **Determinism:** same inputs + pinned model/prompt/schema produce the same
   normalized candidate set; note model nondeterminism explicitly.

Fixtures must include: truncated tail, fenced JSON, mixed valid/invalid lines,
unknown type, out-of-domain relation, table-only window, cross-window relation,
empty document, 1,000-chunk synthetic document, and a provider timeout.

---

## 9. Phased rollout

| Phase | Goal | Main work | Rollback |
|---|---|---|---|
| P0 | Stop losing facts to JSON fragility | JSONL, schema lines, quarantine, fix legacy cache | Keep current adapter behind `extraction.output=legacy-json` |
| P1 | Make the ontology the prompt source | Syntology compiler + digest + generated prompt; remove duplicated vocab | `extraction.ontology.source=static-v1` |
| P2 | Bound fan-out and cost | Window planner, budgets, consolidation, backpressure, partial coverage | `extraction.windowing.enabled=false` falls back to per-chunk |
| P3 | Constrain generation | Subset selector, per-line ID checks, batch semantic validation, SHACL at promotion | `extraction.ontology.subset.enabled=false` uses full schema |

P0 and P1 are independent of the GPU provider and can land first. P2 changes
throughput behavior; gate it behind a tenant flag and compare against the
baseline. P3 is quality work; hold it until the window/budget path is stable.

---

## 10. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Subset selector misses a relevant class | Recall loss | Lexical union + parent closure + `OUT_OF_SUBSET` metric + evaluate on gold; bypass for small ontologies. |
| Strict schema rejects acceptable variants | Lost facts | Quarantine rather than drop; one bounded repair call; allow aliases in the compiled schema. |
| JSONL produces duplicates | Graph noise | Deterministic window/doc dedup; merge evidence. |
| Prefilter drops real knowledge | Silent recall loss | Only skip high-confidence boilerplate; record skipped chunks; coverage metric; sample audit. |
| Model confidence is miscalibrated | False precision | Treat as hint; never use alone for acceptance. |
| Ontology release changes under a replay | Non-reproducible results | Pin release + schema digest in cache key and result. |
| Windowing breaks relations across boundaries | Missing edges | Overlap windows; merge by span; measure cross-boundary gold relations. |
| Cost of ontology embeddings | Startup/latency | Build once per release in Syntology; cache subsets. |
| More moving parts | Complexity | Keep compiler deterministic, selector optional, consolidation deterministic; incremental rollout. |
| Provider-specific structured output differences | Portability | JSONL is the common contract; structured output is an optimization, not a requirement. |

---

## 11. Open decisions

These need ADRs or explicit design decisions before implementation is treated as
committed:

1. **New ADR: ontology-grounded extraction contract.** Freeze JSONL, line
   schema, window metadata, quarantine codes, and version/digest fields.
2. **Schema compiler ownership.** Recommended: Syntology owns compilation;
   extraction engine only consumes the artifact.
3. **Candidate store.** Reuse `ingestion_cache.extraction_results` with a new
   quarantine table, or introduce a dedicated extraction-candidate store. The
   existing contract already isolates candidates from canonical knowledge.
4. **Structured output vs JSONL.** Decide whether a provider-native schema mode
   is allowed as an optimization. JSONL remains the portable contract.
5. **Window defaults.** Validate 2000 tokens / 256 windows / 1024 output tokens
   against the demo corpus and 50-page baseline.
6. **Legacy Pass-1 summaries.** Summaries may still be useful for
   summarization/hierarchy, but they must not feed knowledge extraction. Decide
   whether to keep them as a separate feature or delete them.
7. **Confidence policy.** Decide whether model confidence is stored as a hint,
   ignored, or combined with validation-derived confidence.
8. **Per-tenant budgets and provider selection.** Confirm how quotas interact
   with the existing regex fallback and GPU degraded mode.

---

## Appendix A — Example JSONL response

```text
{"type":"entity","surface":"Halcyon-7","type_id":"MaintenanceSystem","char_start":14,"char_end":23,"confidence":0.94}
{"type":"entity","surface":"coolant pump","type_id":"Component","char_start":64,"char_end":76}
{"type":"relation","subject":"Halcyon-7","subject_type":"MaintenanceSystem","predicate":"requires","object":"coolant pump","object_type":"Component","evidence":"Halcyon-7 requires a coolant pump replacement every ninety days."}
{"type":"attribute","subject":"Halcyon-7","subject_type":"MaintenanceSystem","predicate":"maintenanceInterval","value":"ninety days","evidence":"... every ninety days."}
```

## Appendix B — Line schema (illustrative, compiled per subset)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "synanton.extraction.line.v1",
  "oneOf": [
    { "$ref": "#/$defs/entity" },
    { "$ref": "#/$defs/relation" },
    { "$ref": "#/$defs/attribute" }
  ],
  "$defs": {
    "entity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["type", "surface", "type_id"],
      "properties": {
        "type": { "const": "entity" },
        "surface": { "type": "string", "minLength": 1, "maxLength": 200 },
        "type_id": { "type": "string", "enum": ["Organization", "Supplier", "Product", "Component", "MaintenanceSystem"] },
        "char_start": { "type": "integer", "minimum": 0 },
        "char_end": { "type": "integer", "minimum": 0 },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    },
    "relation": {
      "type": "object",
      "additionalProperties": false,
      "required": ["type", "subject", "subject_type", "predicate", "object", "object_type", "evidence"],
      "properties": {
        "type": { "const": "relation" },
        "subject": { "type": "string", "minLength": 1, "maxLength": 200 },
        "subject_type": { "type": "string", "enum": ["Organization", "Supplier", "Product", "Component", "MaintenanceSystem"] },
        "predicate": { "type": "string", "enum": ["hasSupplier", "requires", "locatedIn"] },
        "object": { "type": "string", "minLength": 1, "maxLength": 200 },
        "object_type": { "type": "string", "enum": ["Organization", "Supplier", "Product", "Component", "MaintenanceSystem"] },
        "evidence": { "type": "string", "minLength": 1, "maxLength": 1000 },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    },
    "attribute": {
      "type": "object",
      "additionalProperties": false,
      "required": ["type", "subject", "subject_type", "predicate", "value"],
      "properties": {
        "type": { "const": "attribute" },
        "subject": { "type": "string", "minLength": 1, "maxLength": 200 },
        "subject_type": { "type": "string", "enum": ["Organization", "Supplier", "Product", "Component", "MaintenanceSystem"] },
        "predicate": { "type": "string", "enum": ["foundedYear", "maintenanceInterval"] },
        "value": { "type": "string", "minLength": 1, "maxLength": 500 },
        "evidence": { "type": "string", "maxLength": 1000 },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    }
  }
}
```

Domain/range is intentionally absent from this schema. Compile it into code-level
checks or per-predicate `if/then` clauses only if the validator remains fast and
readable; the existing Syntology `validate-relation` path is the semantic
authority.

## Appendix C — Prompt skeleton (generated, not hand-maintained)

```text
You extract ontology-grounded knowledge from TEXT.

Ontology: {{ontology_id}} version {{ontology_version}} ({{release_digest}})
Allowed entity types (use the exact type_id):
{{#each classes}}
- {{id}} ({{labels}}): {{description}}
{{/each}}

Allowed relations (use the exact predicate):
{{#each object_properties}}
- {{id}} ({{labels}}): {{domain}} -> {{range}} — {{description}}
{{/each}}

Rules:
- Output one JSON object per line. Never output a JSON array or a wrapper object.
- Use only identifiers from the lists above. Do not invent types or predicates.
- surface/subject/object must be text that appears in TEXT.
- evidence must be a short verbatim quote that supports the relation or attribute.
- If nothing is extractable, output nothing.
- Do not output Markdown fences.

TEXT:
{{window_text}}
```

## Appendix D — Comparison sources

TrustGraph:

- `README.md` — OntologyRAG, ontology-guided extraction, validation scope.
- `docs/tech-specs/ontology.md` — ontology JSON structure, domain/range, cardinality.
- `docs/tech-specs/ontorag.md` — ontology embedder/selector, top-k/threshold, dependency closure, triple validation.
- `docs/tech-specs/ontology-extract-phase-2.md` — simplified entity/relationship/attribute format, code-owned URIs.
- `docs/tech-specs/jsonl-prompt-output.md` — JSONL, per-line schema, truncation resilience.
- `docs/tech-specs/extraction-flows.md` — chunker defaults, extraction pipeline, entity contexts, provenance.
- `docs/tech-specs/large-document-loading.md` — large-document streaming context.

Synanton:

- `java/synflux/.../pipeline/stage/EnrichStage.java` — legacy Pass 1/Pass 2, 300/800 token budgets, cache bug.
- `java/relix/.../loader/Pass2AnalysisParser.java` — direct `readTree`, silent defaults.
- `java/synflux/.../extraction/ExtractionConsumer.java` — sequential per-chunk loop, budgets absent.
- `java/extraction-engine/.../llm/LlmExtractionAdapter.java` — monolithic JSON, surface anchoring, allowed types.
- `java/extraction-engine/src/main/resources/prompts/extraction-v1.txt` — prompt-only schema.
- `java/synflux/.../chunking/SemanticChunker.java` — structure-aware 512-token chunking, atomic tables/lists.
- `java/syntology/.../OntologyService.java` and `OntologyController.java` — `validate-relation` and SHACL/TBox runtime.
- `docs/planning/extraction-consumer-phase2.md` — async consumer, idempotency, batched extraction intent.
- `docs/planning/extracted-mention-graph-contract.md` — deterministic mention IDs, provenance fields, candidate/canonical boundary.
- `docs/planning/extraction-gold-schema.md` — frozen gold and metrics.
- `docs/architecture/decisions/adr-022-extraction-engine-architecture.md` — engine port, offsets, prompt versioning.
- `docs/architecture/proposals/ontology-domain-mapping-v0.3.md` — concept identity, context, candidates vs governed mappings.
