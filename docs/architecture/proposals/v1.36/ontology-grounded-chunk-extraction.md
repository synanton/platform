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

**Revision 2026-11-22:** JSONL is mandatory for multi-fact extraction and the 2,000-token extraction-window proposal is withdrawn. The authoritative segmenting rule is §4.3: preserve 512-token retrieval chunks and split within them into 256–384-token extraction segments, benchmarked against 512. No model-generated offsets or IDs are accepted.

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
4. **Extraction segments are smaller than retrieval chunks.** Retrieval stays
   512-token and structure-aware. Split each retrieval chunk into sentence/
   paragraph-aware 256–384-token extraction segments; never pack multiple
   retrieval chunks into one larger extraction prompt.
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

### 4.3 Extraction segments: smaller than retrieval chunks

The extraction segmenter operates **inside each persisted retrieval chunk**. It does not combine adjacent retrieval chunks into larger LLM inputs.

- Keep \`SemanticChunker\` and its 512-token retrieval contract unchanged: section paths, page provenance, atomic structural units, and table/list semantics remain owned by retrieval chunking.
- Split each retrieval chunk at sentence and paragraph boundaries. Start with a **256-token target**, a **384-token hard content ceiling**, and **32–48 tokens** of overlap. Benchmark 256/384/512-token units with 32/48/64-token overlap on the frozen gold before fixing production defaults.
- Never concatenate multiple 512-token retrieval chunks to reach a target size. A short final segment is valid. Do not split inside a sentence unless a single sentence exceeds the hard ceiling; record that exceptional split.
- Tables remain structurally aware: segment by header plus bounded row groups. Never force an oversized table into one prompt. Preserve row-group provenance and mark exceptional splits.
- Each segment records \`segment_id\`, parent \`chunk_id\`, chunk-local character range, document offsets when available, token count, overlap range, and text digest. The model never supplies these values.
- Overlap is context only. Deduplicate by deterministic source anchoring and span, retaining all supporting chunk/segment references.
- Do not silently switch the unprocessed tail to regex/dictionary extraction when a budget is exhausted. Stop scheduling, persist the reason, and report \`PARTIAL\` with exact processed/eligible segment counts and coverage.

This deliberately trades more small calls for narrower semantic context and better evidence localization. Throughput must come from bounded GPU batching and admission control, not from making each extraction prompt larger.

### 4.3.1 Token accounting and scheduling

Token counts are computed by the configured model tokenizer (or a documented conservative estimator when tokenizer access is unavailable). The planner and scheduler own all token arithmetic. Track separately segment content tokens, ontology/system-prompt tokens, reserved completion tokens, queued prompt tokens, in-flight requests, and per-document/per-tenant budgets.

Admit a request only when both context capacity and GPU/KV-cache admission permit it. Use a bounded queue and backpressure; do not equate a fixed thread-pool size with GPU capacity. Record model, tokenizer, and runtime revisions.

Recover cross-segment relations through overlap and deterministic anchoring. Do not enlarge every segment to solve rare boundary cases; measure cross-boundary relation recall explicitly.

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

**JSONL is the canonical multi-fact LLM output contract.** Each non-empty line is exactly one UTF-8 JSON object. A response is zero or more newline-delimited records; it is never a JSON array or wrapper object. This applies to every knowledge-extraction path, including any retained legacy path. Provider-native structured output may be used only if the adapter normalizes it to this same JSONL contract before validation and persistence.

\`\`\`text
{"type":"entity","surface":"Acme Corporation","type_id":"Organization"}
{"type":"entity","surface":"Beta Ltd","type_id":"Supplier"}
{"type":"relation","subject":"Acme Corporation","subject_type":"Organization","predicate":"hasSupplier","object":"Beta Ltd","object_type":"Supplier","evidence":"Acme Corporation is supplied by Beta Ltd."}
{"type":"attribute","subject":"Acme Corporation","subject_type":"Organization","predicate":"foundedYear","value":"1999","evidence":"Acme was founded in 1999."}
\`\`\`

Contract rules:

- \`type\` is the discriminator: \`entity\`, \`relation\`, or \`attribute\`.
- \`type_id\` and \`predicate\` must be identifiers from the pinned compiled ontology subset. The model must not invent identifiers or IRIs.
- The model returns exact surface strings and verbatim evidence quotes. It does **not** return character offsets, chunk IDs, segment IDs, mention IDs, relation IDs, canonical IDs, or graph IRIs.
- Deterministic code anchors each surface/evidence quote in segment text, calculates offsets, maps them to the parent retrieval chunk/document, and creates deterministic identities. Ambiguous or absent anchors are quarantined; never guess an offset.
- \`confidence\` is optional and informational only. It is not calibrated truth and must not independently gate acceptance.
- A relation requires both endpoints and evidence. An attribute requires a subject, predicate, value, and evidence.
- Each line is independently schema-validated. One malformed line must not discard valid neighboring lines.
- The final valid JSON object is accepted even without a trailing newline. If the provider reports a length/token-limit finish reason, complete preceding lines are retained; an incomplete final line is quarantined as \`TRUNCATED\`. A parseable complete final object remains valid.
- Blank lines may be ignored and counted. Markdown fences, prose prefixes, wrapper arrays, and wrapper objects are protocol violations; do not silently strip prose or reinterpret monolithic JSON as JSONL.
- Enforce a configurable UTF-8 byte limit per line. Start with **8 KiB** and test long evidence/Unicode cases; the previous 2 KiB proposal is too tight for legitimate relation evidence.

JSON Schema (Appendix B) is compiled per ontology subset. The adapter returns a typed \`ExtractionResult\` to consumers; JSONL is the model/adapter interchange format, not a requirement that downstream graph APIs store raw text.

### 4.7 Validation, quarantine, and consolidation

Process the response as a stream of independent lines:

| Step | Check | Failure code / behavior |
|---:|---|---|
| 1 | UTF-8 decode and per-line byte limit | \`LINE_INVALID_UTF8\`, \`LINE_TOO_LARGE\` |
| 2 | Parse one JSON object from one line | \`JSON_PARSE\`; incomplete tail with provider truncation = \`TRUNCATED\` |
| 3 | Validate against the compiled line schema | \`SCHEMA\` |
| 4 | Check \`type_id\`/\`predicate\` against the pinned subset | \`OUT_OF_SUBSET\` |
| 5 | Deterministically anchor surface and evidence | \`UNANCHORED\`, \`AMBIGUOUS_ANCHOR\` |
| 6 | Check endpoint presence and relation shape | \`ENDPOINT_MISSING\`, \`SELF_RELATION\` |
| 7 | Segment-local dedup by anchored span and ontology IDs | \`DUPLICATE\` (counted) |
| 8 | Document-level deterministic merge | Merge evidence and source references |
| 9 | Batch semantic validation (TBox domain/range + predicate SHACL) | \`DOMAIN_RANGE\`, \`SHACL\` |
| 10 | Promotion/canonicalization policy | Policy-specific result |

A response-level protocol error (for example, a wrapper array or leading prose) is recorded separately from line-level failures. Never convert a protocol error into an empty successful extraction.

Quarantine records include tenant/source/segment identity, line number, reason code, bounded raw line, provider finish reason, ontology release/schema digest, model and prompt revisions, and timestamp. Raw prompts/responses and quarantined lines require tenant isolation, access control, bounded retention, and deletion with the source generation. Do not put unbounded raw responses into ordinary application logs.

Consolidation remains deterministic: union accepted candidates, merge evidence spans and source chunk IDs, and never merge across ontology class IDs. No LLM sees a concatenation of all segment outputs. If alias/conflict resolution later requires a model, it runs as a separate bounded task with its own JSONL schema, budget, and cache key.

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

**Cache key** for a segment result:

\`\`\`text
sha256(
  tenant | source_version | ontology_release | schema_digest | prompt_digest |
  model_id | model_revision | provider | temperature | top_p |
  max_output_tokens | tokenizer_version | segment_text_sha256 |
  subset_digest | validator_version
)
\`\`\`

Store validated candidate records plus quarantine counts and usage. Retain the raw response only under an explicit, tenant-isolated retention policy. A cache hit must be replayable without calling the model.

**Idempotency:** re-running a segment replaces that segment's candidate set; it never appends. Document consolidation and graph commit are deterministic over ordered segment keys. This extends the existing consumer's \`(source identity, version_id)\` idempotency.

**Budgets:** account independently for segment content, ontology/system prompt, reserved completion, per-document prompt/completion totals, and maximum calls. The proposed initial profile is 256-token target / 384-token hard segment ceiling / 32-token overlap / 512-token completion reservation. Benchmark alternatives before production rollout; do not retain the former 2,000-token input / 100-token overlap / 1,024-token completion defaults.

When a budget is reached, finish and persist completed segments, stop scheduling new model work, and mark the document \`coverage=PARTIAL\` with exact processed/eligible counts and a reason. Do not silently switch the remainder to a different provider; that would make the output semantically inconsistent and evaluation incomparable.

**Backpressure:** use bounded queues, per-tenant fair scheduling, and admission based on actual GPU/KV-cache capacity and queued prompt tokens. A global capacity-aware limiter and circuit breaking protect the model runtime. Do not create a thread pool per document or assume a fixed concurrency of 16 is safe.

### 4.11 Failure handling

| Failure | Handling |
|---|---|
| Provider reports token-limit finish; final line is incomplete | Retain all independently parseable complete lines; quarantine only the incomplete tail as \`TRUNCATED\`. |
| Invalid JSONL line | Quarantine that line; continue with other lines. |
| Wrapper JSON array/object, Markdown fence, or prose prefix | Record a response-level protocol violation; do not coerce it into JSONL or report empty success. |
| Schema-valid but out-of-subset identifier | Quarantine as \`OUT_OF_SUBSET\`; count; do not coerce. |
| Unanchorable/ambiguous surface or evidence | Quarantine as \`UNANCHORED\`/\`AMBIGUOUS_ANCHOR\`; dependent relation is not promoted. |
| Provider 5xx/timeout | Retry the same segment with backoff; cache key makes retry idempotent. |
| Ontology release missing/digest mismatch | Fail closed; do not extract against a guessed ontology. |
| Semantic validation rejects relation | Keep candidate + rejection reason for analytics; do not write accepted edge. |
| Budget exhausted | Stop scheduling and report \`PARTIAL\` with exact coverage; no silent provider fallback. |

## 5. Concrete changes in Synanton

| # | Area | Change | Files (indicative) |
|---:|---|---|---|
| C1 | Extraction output | Make JSONL canonical for all multi-fact extraction; per-line parse/schema/quarantine, truncation recovery, protocol errors. | `java/extraction-engine/.../llm/LlmExtractionAdapter.java`, `.../json/Json.java` |
| C2 | Extraction contract | Extend `ExtractionResult`/usage with window ID, accepted lines, quarantine counts, truncation/coverage flags. | `java/extraction-engine/.../ExtractionResult.java`, `ExtractionContext.java` |
| C3 | Schema validation | Add a JSON Schema validator dependency and compile a line schema from the selected subset. | `java/extraction-engine` |
| C4 | Ontology compiler | New Syntology component and endpoint producing `ExtractionSchema` + digest from a release. | `java/syntology/.../domain/service`, `api/rest/OntologyController.java` |
| C5 | Subset selector | Lexical + embedding selector with dependency closure, bounds, cache, and miss metrics. | `java/syntology` or shared ontology client; `java/extraction-engine` |
| C6 | Segment planner | Split each 512-token retrieval chunk into sentence/paragraph-aware 256–384-token segments; preserve offsets, overlap, and coverage. | New `java/synflux/.../extraction/ExtractionWindowPlanner.java` |
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

All tunables belong in \`application.yml\` with environment overrides, not scattered Java defaults. Values below are initial experiment settings; the frozen-gold benchmark decides the production profile.

| Property | Initial value | Purpose |
|---|---:|---|
| \`synflux.extraction.segment.target-tokens\` | 256 | Preferred extraction segment size. |
| \`synflux.extraction.segment.max-tokens\` | 384 | Hard content-token ceiling, except recorded structural edge cases. |
| \`synflux.extraction.segment.overlap-tokens\` | 32 | Initial overlap; benchmark 32/48/64. |
| \`synflux.extraction.segment.max-per-document\` | 1024 | Safety cap for smaller segments; budget exhaustion is explicit. |
| \`synflux.extraction.segment.max-output-tokens\` | 512 | Per-segment completion reservation; calibrate against JSONL line yield. |
| \`synflux.extraction.document.max-prompt-tokens\` | 500000 | Per-document prompt-token budget; tune from measured workload. |
| \`synflux.extraction.document.max-completion-tokens\` | 100000 | Per-document completion-token budget. |
| \`synflux.extraction.document.max-llm-calls\` | 1024 | Hard call cap; exhaustion produces \`PARTIAL\`. |
| \`synflux.extraction.concurrency.per-tenant\` | 1 | Initial fairness limit; scheduler may admit more when capacity allows. |
| \`synflux.extraction.concurrency.global\` | capacity-aware | Bounded by measured GPU/KV-cache capacity, not a fixed thread count. |
| \`synflux.extraction.ontology.subset.top-k\` | 12 | Similarity matches before dependency closure. |
| \`synflux.extraction.ontology.subset.threshold\` | 0.35 | Initial similarity floor; tune on subset recall. |
| \`synflux.extraction.ontology.subset.max-classes\` | 40 | Prompt bound after required dependency closure. |
| \`synflux.extraction.ontology.subset.max-properties\` | 80 | Prompt bound after required dependency closure. |
| \`synflux.extraction.ontology.subset.bypass-class-count\` | 20 | Use full schema for small ontologies. |
| \`synflux.extraction.validation.max-line-bytes\` | 8192 | UTF-8 JSONL line limit; test evidence and Unicode. |
| \`synflux.extraction.validation.repair-attempts\` | 0 | No automatic whole-response repair; retry at segment granularity only under explicit policy. |
| \`synflux.extraction.prefilter.enabled\` | true | Skip only deterministic/high-confidence boilerplate, with a recorded reason. |

The 256/384-token segment sizes, overlap, and budgets are hypotheses to test, not claims of an established optimum. Never increase segment size solely to reduce call count without measuring relation recall and evidence localization.

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

**P0 — JSONL and schema safety**

- Every multi-fact knowledge-extraction path uses JSONL as its canonical model/adapter interchange format; no monolithic JSON arrays/objects.
- Each line is independently parsed and schema-validated. Invalid lines are quarantined without losing valid neighboring records.
- A truncated response preserves every complete record; only an incomplete tail is quarantined as \`TRUNCATED\`.
- Wrapper arrays/objects, Markdown fences, and prose prefixes are protocol violations, not accepted/normalized responses.
- The model emits no offsets or IDs; deterministic code anchors exact surfaces and evidence and computes all offsets/identities.
- The legacy Pass-2 cache bug is fixed or the knowledge-extraction path retired.

**P1 — ontology contract**

- Syntology returns an \`ExtractionSchema\` with release + schema digests.
- The prompt contains only identifiers from the compiled schema; changing the ontology release changes the prompt and cache key without code changes.
- A fixture ontology with a broken domain/range fails compilation closed.

**P2 — smaller segments and bounded fan-out**

- Ordinary extraction segments are at most 384 content tokens and never combine multiple retrieval chunks.
- The 256/384/512 comparison reports entity F1, relation F1, evidence-anchor accuracy, valid-line rate, cost, p95 latency, throughput, and GPU utilization.
- A 200-chunk document respects per-segment, per-document token, and call budgets; no unbounded prompt is constructed.
- Budget exhaustion produces \`PARTIAL\` + exact coverage, not \`KNOWLEDGE_READY\`.
- Throughput is compared at equal GPU-time/token budgets; consolidation is deterministic across reruns.

**P3 — ontology subsetting and validation**

- Subset selection reduces prompt tokens by a measured margin with no more than an agreed recall loss on the gold.
- Out-of-subset identifiers are quarantined and measurable.
- Domain/range + SHACL rejections at commit are reduced compared with P0.
- \`validate-relation\` is called in batches, not per relation.

## 8. Evaluation plan

Reuse the ADR-022 harness and frozen gold. Compare extraction segment sizes **256, 384, and 512 tokens** and overlaps **32, 48, and 64 tokens**. Keep the 512-token retrieval chunker fixed so the experiment isolates extraction granularity.

Report:

1. Entity precision/recall/F1 and relation precision/recall/F1.
2. Evidence quote and deterministic anchor exactness; false/ambiguous anchor rates.
3. JSONL valid-line rate, per-line schema failures, truncation recovery, and quarantined records by reason.
4. Prompt/completion tokens, calls/document, p50/p95 latency, throughput, GPU utilization/KV-cache pressure, and cost/document.
5. Cross-segment relation recall and duplicate/false-merge rates.
6. Coverage-quality trade-off under equal GPU-time and token budgets.
7. Ontology subset recall: gold entities/relations whose class/predicate was absent from the selected subset.
8. Ontology conformance before/after domain/range and SHACL validation.
9. Reproducibility with pinned source, ontology/schema, prompt, model, tokenizer, and validator revisions; explicitly report residual model nondeterminism.

Do not select a segment size on throughput alone. A larger segment is not an improvement if relation recall, evidence localization, or extraction precision regresses.

Fixtures include: truncated final JSONL line with preceding complete records, mixed valid/invalid lines, wrapper-array/prose protocol violations, unknown type, out-of-domain relation, Unicode and long-evidence lines near the byte limit, 256/384/512-token segments, table row groups, cross-segment relation, empty document, 1,000-chunk synthetic document, provider timeout, and budget exhaustion.

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

These need ADRs or explicit design decisions before implementation is treated as committed:

1. **New ADR: ontology-grounded extraction contract.** Freeze JSONL, line schema, segment metadata, quarantine codes, and version/digest fields.
2. **Schema compiler ownership.** Recommended: Syntology owns compilation; extraction engine only consumes the artifact.
3. **Candidate store.** Reuse \`ingestion_cache.extraction_results\` with a new quarantine table, or introduce a dedicated extraction-candidate store.
4. **Provider structured output.** It may be an internal optimization only if the adapter emits the same canonical JSONL records; it must not create a second public extraction contract.
5. **Segment defaults.** Benchmark 256/384/512-token extraction segments with 32/48/64-token overlap. Keep retrieval chunks at 512 tokens; do not introduce 2,000-token extraction windows.
6. **Legacy Pass-1 summaries.** Summaries may remain a separate summarization/hierarchy feature, but must not feed knowledge extraction.
7. **Confidence policy.** Decide whether model confidence is stored as a hint, ignored, or combined with validation-derived confidence.
8. **Per-tenant budgets and GPU admission.** Confirm quota policy and runtime capacity signals; budget exhaustion must not silently switch providers.

## Appendix A — Example JSONL response

No model-generated offsets or IDs are part of the wire contract:

\`\`\`text
{"type":"entity","surface":"Halcyon-7","type_id":"MaintenanceSystem"}
{"type":"entity","surface":"coolant pump","type_id":"Component"}
{"type":"relation","subject":"Halcyon-7","subject_type":"MaintenanceSystem","predicate":"requires","object":"coolant pump","object_type":"Component","evidence":"Halcyon-7 requires a coolant pump replacement every ninety days."}
{"type":"attribute","subject":"Halcyon-7","subject_type":"MaintenanceSystem","predicate":"maintenanceInterval","value":"ninety days","evidence":"... every ninety days."}
\`\`\`

## Appendix B — Line schema (illustrative, compiled per subset)

The schema is a discriminated union with \`additionalProperties: false\`. The example shows the entity line; relation and attribute variants follow the same closed-object rule. No offset, ID, provenance, or graph-identity field is accepted from the model.

\`\`\`json
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
      "required": ["type", "subject", "subject_type", "predicate", "value", "evidence"],
      "properties": {
        "type": { "const": "attribute" },
        "subject": { "type": "string", "minLength": 1, "maxLength": 200 },
        "subject_type": { "type": "string", "enum": ["Organization", "Supplier", "Product", "Component", "MaintenanceSystem"] },
        "predicate": { "type": "string", "enum": ["foundedYear", "maintenanceInterval"] },
        "value": { "type": "string", "minLength": 1, "maxLength": 500 },
        "evidence": { "type": "string", "minLength": 1, "maxLength": 1000 },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    }
  }
}
\`\`\`

Actual enum values are generated from the pinned ontology subset. The adapter validates each line independently and produces typed candidates; this is not a persistence schema.

## Appendix C — Prompt skeleton (generated, not hand-maintained)

\`\`\`text
You extract ontology-grounded knowledge from TEXT.

Ontology: {{ontology_id}} version {{ontology_version}} ({{release_digest}})
Allowed entity types and predicates are listed below. Use exact identifiers.
{{compiled_subset}}

Rules:
- Output one JSON object per line. Never output an array or wrapper object.
- Do not output Markdown fences or explanatory prose.
- Use only identifiers from the supplied ontology subset.
- Return exact surface strings and short verbatim evidence quotes from TEXT.
- Do not return offsets, IDs, IRIs, chunk/segment metadata, or provenance fields.
- If nothing is extractable, output nothing.

TEXT:
{{segment_text}}
\`\`\`



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
