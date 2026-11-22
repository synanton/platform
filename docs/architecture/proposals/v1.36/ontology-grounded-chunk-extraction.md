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
3. The ontology is only an allowed-type hint. There is no per-segment subset, no
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
TrustGraph's ontology-selection and JSONL ideas, but not its chunker. The design
below keeps extraction segmentation local to each retrieval chunk and adds
bounded scheduling, deterministic consolidation, and quarantine. The operational
surface is intentionally kept inside the existing Syntology, Synflux consumer,
and extraction-engine services; it does not require a new service per logical
component.

**Revision 2026-11-22:** JSONL is mandatory for multi-fact extraction and the 2,000-token extraction-window proposal is withdrawn. The authoritative segmenting rule is §4.3: preserve 512-token retrieval chunks and split within them into 256–384-token extraction segments, benchmarked against 512. No model-generated offsets or IDs are accepted.

**Recommendation:** implement OGCE in four increments:

1. **P0 — JSONL contract safety.** Make JSONL mandatory for all multi-fact
   extraction; validate each line independently, preserve complete lines on
   truncation, quarantine invalid records, prohibit model-generated offsets/IDs,
   and fix or retire the legacy Pass-2 path.
2. **P1 — ontology contract.** Syntology compiles the pinned ontology release
   into a versioned extraction schema (classes, aliases, properties,
   domain/range, digest); prompts and line schemas are generated from it.
3. **P2 — smaller extraction segments and bounded scheduling.** Keep 512-token
   retrieval chunks unchanged; split within each chunk into sentence/paragraph-
   aware 256–384-token extraction segments; benchmark against 512 tokens and
   overlap variants. Use GPU-capacity-aware batching, per-document token/call
   budgets, deterministic consolidation, and explicit partial coverage.
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
| Internal text unit for ontology matching | Whole chunk | Sentence split (NLTK) | Adopt sentence-level matching inside an extraction segment. |
| Extraction unit | One retrieval chunk per LLM call | One retrieval chunk per LLM call | Both fan out. Synanton splits each retrieval chunk into smaller extraction segments and adds budgets. |
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
| Dedup | Relix canonicalizer + per-document local map; Pass-2 can duplicate | Entity registry during extraction | Add per-segment registry and deterministic merge; keep canonical resolution downstream. |
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

### 4.2 Components and ownership (logical modules, not new services)

The boxes below are responsibilities and interfaces, not a proposal to deploy
six new services. P0/P2 should reuse the existing extraction-engine adapter,
Synflux asynchronous consumer, candidate persistence, and GraphWriter path.
Only the ontology schema artifact/API is a new Syntology capability in P1.

\`\`\`text
Syntology (existing service)
  pinned ontology release
       |
  compile ExtractionSchema (pure, deterministic library operation)
       |
  versioned schema artifact + digest
       v
Synflux extraction consumer (existing worker)
  plan segments + coverage + budgets
       |
  retrieve/cache ontology subset (optional P3 module)
       |
  ExtractionEngineAdapter (existing port; JSONL contract)
       |
  LineValidator (adapter/library module)
       |
  deterministic candidate consolidation
       |
  existing candidate store + quarantine store
       |
  GraphWriter / Relix (existing path)
       |
  batched TBox/domain-range + SHACL validation
\`\`\`

| Responsibility | Initial owner | Boundary / implementation constraint |
|---|---|---|
| Extraction schema compilation | Syntology library + existing API | Pure function of immutable ontology release; no new runtime service. |
| Segment planning and budget accounting | Synflux extraction consumer | Extend the existing consumer; do not create a second scheduler. |
| Ontology subset selection | Optional Syntology library | Keep disabled/full-schema fallback until recall is measured. |
| JSONL parsing, schema and anchoring | extraction-engine adapter | One shared implementation for all multi-fact extraction paths. |
| Candidate consolidation | extraction-engine / consumer library | Deterministic in-process operation; no model call in the default path. |
| Batched semantic validation | Existing Syntology validation API | Extend/batch existing validation; do not create a parallel validator. |

The implementation may keep these as packages/classes with unit-testable
contracts. A new deployable, database, queue, or public API requires a separate
decision backed by measured need. The compiler and subset selector are not P0
prerequisites: P0 can use a pinned static schema artifact generated offline.
This reduces the first delivery to JSONL correctness and deterministic anchoring
before ontology automation is introduced.

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

Subset selection is an optimization, never a correctness boundary. The initial
top-k/threshold/bounds are experimental defaults and must be tuned against the
frozen gold rather than promoted directly to production.

1. Lexical match class labels, aliases, and glossary terms in the segment.
2. Semantic match segment sentences against a precomputed, release-pinned
   ontology embedding index. Start with top-k 12 and threshold 0.35.
3. Close dependencies: parent classes, property domains/ranges, applicable
   properties, and declared inverse properties.
4. Enforce class/property prompt budgets. If dependency closure exceeds the
   budget, do not silently drop required domain/range elements. Mark the subset
   incomplete and use the full-schema fallback for that segment, or stop with
   an explicit \`SUBSET_BUDGET_EXCEEDED\` if the full schema also exceeds the
   model context budget.
5. Bypass selection for small ontologies (initially <=20 classes).
6. Cache by ontology release, schema digest, embedding model revision, selector
   revision, and segment text digest.

**Feedback and remediation loop**

- Record selected IDs, selector scores, closure additions, truncation/fallback
  status, and every \`OUT_OF_SUBSET\` candidate.
- Report out-of-subset counts and rates by ontology release, class/predicate,
  selector revision, and tenant (without exposing tenant data in aggregate
  dashboards).
- A single miss is not an automatic threshold adjustment. First determine
  whether the gold fact is absent from the compiled schema, lexical/semantic
  retrieval missed it, or dependency closure/budgeting removed it.
- If a valid ontology identifier is outside the selected subset, retry that
  segment once with a widened subset (for example top-k doubled) or the full
  schema if it fits. Keep the original and retry outcomes linked; never silently
  rewrite the first result.
- If the miss rate exceeds the provisional 2% of otherwise schema-valid
  candidates for a release over a minimum sample of 500 candidates, pause
  subset rollout for that release and route a sample to the ontology steward.
  The steward decides whether to add aliases/definitions, revise selector
  settings, or increase prompt budgets. No online self-tuning of thresholds.
- If full-schema fallback is too large, fail closed with explicit partial
  coverage and create a review item; do not omit ontology constraints.

The ontology embedding index is built once per release and reused. Its model
revision and vector-generation configuration are part of the cache key.

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
- Enforce a configurable UTF-8 byte limit per line. Start with **16 KiB**; measure p50/p95/p99/max evidence bytes on the frozen gold and require zero legitimate gold lines to exceed the bound. If a line is too large, retry once for a shorter verbatim evidence excerpt; never truncate serialized JSON or silently alter an evidence quote.

JSON Schema (Appendix B) is compiled per ontology subset. The adapter returns a typed \`ExtractionResult\` to consumers; JSONL is the model/adapter interchange format, not a requirement that downstream graph APIs store raw text.

### 4.7 Validation, quarantine, and consolidation

Process the response as independent JSONL lines:

| Step | Check | Failure code / behavior |
|---:|---|---|
| 1 | UTF-8 decode and per-line byte limit | \`LINE_INVALID_UTF8\`, \`LINE_TOO_LARGE\` |
| 2 | Parse one JSON object from one line | \`JSON_PARSE\`; incomplete final line with provider truncation = \`TRUNCATED\` |
| 3 | Validate against compiled line schema | \`SCHEMA\` |
| 4 | Check IDs against pinned schema/subset | \`OUT_OF_SUBSET\` |
| 5 | Deterministically anchor surface and evidence | \`UNANCHORED\`, \`AMBIGUOUS_ANCHOR\` |
| 6 | Check endpoints and relation shape | \`ENDPOINT_MISSING\`, \`SELF_RELATION\` |
| 7 | Segment-local dedup by anchored span and ontology IDs | \`DUPLICATE\` (counted) |
| 8 | Document-level deterministic merge | Merge evidence and source references |
| 9 | Batch TBox/domain-range and predicate SHACL checks | \`DOMAIN_RANGE\`, \`SHACL\` |
| 10 | Promotion/canonicalization policy | Policy-specific result |

A response-level protocol error (wrapper array/object, Markdown fence, or
leading prose) is distinct from line-level failures. Never coerce it into
JSONL or report an empty successful extraction.

**Line-size and evidence policy**

- Start with a configurable **16 KiB UTF-8 line limit**, not 8 KiB. This is a
  defensive transport bound, not a claim about corpus evidence length.
- Measure the gold-corpus evidence-quote byte-length distribution (p50/p95/p99,
  maximum, Unicode-heavy cases) before fixing the production limit. The target
  is zero legitimate gold evidence lines rejected by the limit.
- The model should return a concise, verbatim, independently anchorable evidence
  excerpt, not an entire page/table/legal section. A line over the limit gets
  one bounded retry requesting a shorter quote while preserving the same fact.
- Do not truncate JSON bytes or silently truncate evidence after generation:
  either operation can corrupt syntax or invalidate exact anchoring. If the
  retry still exceeds the limit, quarantine as \`LINE_TOO_LARGE\` with size
  metadata and retain the candidate only as an unpromoted diagnostic if policy
  permits. The source remains available for a later segment-level retry.
- Table evidence should be a bounded header + row/cell excerpt that anchors to
  the source, not a serialized full table.

**Quarantine storage and review**

Quarantine is an operational work queue, not a write-only log. Store bounded raw
lines and metadata encrypted at rest using the platform's tenant-scoped key
management/envelope-encryption facility. Enforce tenant isolation in storage
and authorization; log every read, export, replay, and disposition with actor,
time, reason, and source/segment identity. Default retention is 30 days, with a
hard maximum of 90 days unless an approved legal/security retention policy
requires otherwise. Source-generation deletion cascades to quarantine payloads
and derived raw prompts/responses. Retention expiry is enforced automatically.

- Extraction/runtime owner reviews transport/schema/truncation spikes daily
  during rollout and weekly after stabilization.
- Ontology steward reviews \`OUT_OF_SUBSET\` and \`DOMAIN_RANGE\` samples weekly,
  and immediately when a release-level threshold trips.
- \`LINE_TOO_LARGE\` is reviewed as a prompt/evidence-contract issue; repeated
  instances trigger schema/prompt revision, not an increased limit by default.
- Every disposition is one of: fix source/schema, add ontology alias/definition,
  tune selector, revise prompt, retry segment, accept as non-promotable, or
  close as expected invalid output. Decisions feed regression fixtures.

Consolidation remains deterministic: union accepted candidates, merge evidence
spans and source chunk references, and never merge across ontology class IDs.
No LLM sees a concatenation of all segment outputs. Raw quarantine data is not
copied into ordinary application logs.

### 4.8 Identity and resolution boundary

- Extraction emits **candidates**, not canonical entities. This matches the
  existing extracted-mention contract.
- `mention_id` remains deterministic:
  `sha256(tenant | source_version | chunk_id | chunk_offset_start | chunk_offset_end | surface)`.
- When a window spans chunks, compute the chunk and offset from the anchored
  document span; do not assign the segment ID as the chunk.
- `type_id` is an ontology class identifier, not a canonical instance ID.
- Canonical entity creation and `:RESOLVES_TO` stay downstream (Equalix /
  Syntology per ADR-030 and v0.3). Do not merge same-surface mentions across
  classes.
- Preserve the v0.3 distinction between lexical expression, concept, instance,
  candidate, and governed assertion.

### 4.9 Provenance

Every accepted candidate retains:

- segment ID and prompt/schema/model digests;
- source chunk ID(s) and character offsets;
- evidence quote and its span;
- ontology release and schema digest;
- `finish_reason`, truncation flag, and whether the line came from a repair call;
- for relations, subject/object mention IDs after anchoring.

This preserves the chain
`source → chunk → segment → mention candidate → relation candidate → validation →
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
| C2 | Extraction contract | Extend `ExtractionResult`/usage with segment ID, accepted lines, quarantine counts, truncation/coverage flags. | `java/extraction-engine/.../ExtractionResult.java`, `ExtractionContext.java` |
| C3 | Schema validation | Add a JSON Schema validator dependency and compile a line schema from the selected subset. | `java/extraction-engine` |
| C4 | Ontology compiler | New Syntology component and endpoint producing `ExtractionSchema` + digest from a release. | `java/syntology/.../domain/service`, `api/rest/OntologyController.java` |
| C5 | Subset selector | Lexical + embedding selector with dependency closure, bounds, cache, and miss metrics. | `java/syntology` or shared ontology client; `java/extraction-engine` |
| C6 | Segment planner | Split each 512-token retrieval chunk into sentence/paragraph-aware 256–384-token segments; preserve offsets, overlap, and coverage. | Extend `java/synflux/.../extraction/ExtractionConsumer.java` with an in-process segment planner. |
| C7 | Consumer | Replace sequential per-chunk loop with window batches, global/per-tenant semaphores, per-document budgets, partial status. | `java/synflux/.../extraction/ExtractionConsumer.java` |
| C8 | Consolidation | Deterministic merge of segment results into document candidates; no LLM conflict-resolution pass in the first release. | New `java/synflux/.../extraction/CandidateConsolidator.java` |
| C9 | Quarantine | Persist rejected lines with reason codes and raw line; metrics. | `java/ingestion-cache` schema + client; new table/migration |
| C10 | Legacy path | Stop using `EnrichStage` Pass 1/Pass 2 for knowledge extraction; fix the cache bug if retained for summaries; use the mustache templates or delete them. | `java/synflux/.../pipeline/stage/EnrichStage.java`, resources |
| C11 | Parser | Remove silent defaults (`type="Unknown"`, `confidence=1.0`); require schema-valid fields; keep tolerant-but-counted behavior. | `java/relix/.../loader/Pass2AnalysisParser.java` |
| C12 | Validation | Batch `validate-relation` calls; add entity class validation and promotion-time SHACL. | `java/syntology`, `java/synflux/.../extraction/graph/GraphWriter.java` |
| C13 | Provenance | Carry segment/schema/ontology digests and per-line evidence through to `extraction_results` and graph commit. | `java/synflux`, `java/ingestion-cache`, `java/relix` |

Do not put ontology parsing or SHACL logic in the extraction engine. Syntology
compiles; the engine consumes an artifact.

---

## 6. Suggested configuration

All tunables belong in \`application.yml\` with environment overrides. Values
below are experiment starting points, not production promises.

| Property | Initial value | Purpose |
|---|---:|---|
| \`synflux.extraction.segment.target-tokens\` | 256 | Preferred segment size. |
| \`synflux.extraction.segment.max-tokens\` | 384 | Hard content-token ceiling, except recorded structural edge cases. |
| \`synflux.extraction.segment.overlap-tokens\` | 32 | Initial overlap; benchmark 32/48/64. |
| \`synflux.extraction.segment.max-per-document\` | 1024 | Safety cap; exhaustion is explicit. |
| \`synflux.extraction.segment.max-output-tokens\` | 512 | Completion reservation; calibrate to line yield. |
| \`synflux.extraction.document.max-prompt-tokens\` | 500000 | Per-document prompt-token budget. |
| \`synflux.extraction.document.max-completion-tokens\` | 100000 | Per-document completion-token budget. |
| \`synflux.extraction.document.max-llm-calls\` | 1024 | Hard call cap; exhaustion produces \`PARTIAL\`. |
| \`synflux.extraction.concurrency.per-tenant\` | 1 | Initial fairness limit. |
| \`synflux.extraction.concurrency.global\` | capacity-aware | Measured GPU/KV-cache admission, not fixed thread count. |
| \`synflux.extraction.ontology.subset.top-k\` | 12 | Similarity matches before closure. |
| \`synflux.extraction.ontology.subset.threshold\` | 0.35 | Initial similarity floor; tune on recall. |
| \`synflux.extraction.ontology.subset.max-classes\` | 40 | Prompt budget; overflow uses full-schema fallback or explicit stop. |
| \`synflux.extraction.ontology.subset.max-properties\` | 80 | Prompt budget; overflow uses full-schema fallback or explicit stop. |
| \`synflux.extraction.ontology.subset.bypass-class-count\` | 20 | Use full schema for small ontologies. |
| \`synflux.extraction.validation.max-line-bytes\` | 16384 | Initial UTF-8 line bound; validate against gold distribution. |
| \`synflux.extraction.validation.evidence-retry-attempts\` | 1 | One bounded retry for overlong evidence. |
| \`synflux.extraction.validation.repair-attempts\` | 0 | No whole-response repair. |
| \`synflux.extraction.quarantine.retention-days\` | 30 | Default; hard maximum 90 absent approved exception. |
| \`synflux.extraction.prefilter.enabled\` | true | Skip only deterministic/high-confidence boilerplate with recorded reason. |

For a 50-page document with approximately 200 near-full 512-token retrieval
chunks, the planning estimate is approximately 400 extraction calls when each
chunk is split into two segments (256-token target with a 384-token hard ceiling,
or a 384-token target where the final tail is separate), versus approximately
200 calls in 512-token mode. This is an illustration, not a fixed prediction:
actual counts depend on sentence/paragraph/table boundaries, short chunks, and
overlap. The segment planner's exact count is authoritative and is recorded
per document.

The cost model must report model prompt/completion tokens, calls, GPU-seconds,
queue wait, wall-clock p50/p95, and energy/cost where the runtime exposes it.
Do not publish a dollar target without a declared model/provider price or
hardware amortization profile. The reference benchmark must pin GPU model and
memory, model quantization/revision, runtime, batch size, concurrency, tokenizer,
and tenant workload mix.

## 7. Metrics and acceptance gates

### 7.1 Metrics

In addition to existing counters, record:

- segment/call counts, prompt and completion tokens, queue wait, in-flight
  requests, GPU-seconds, KV-cache pressure, wall-clock duration;
- JSONL valid-line rate, parse/schema/protocol failures, truncation recovery,
  quarantine rate by reason, line-byte distribution;
- selected ontology IDs, selector score/closure, subset fallback, and
  \`OUT_OF_SUBSET\` rates by release and selector revision;
- per-document eligible/processed segments, coverage ratio, and partial reason;
- cross-segment relation candidates, reconciliations, and gold boundary outcomes;
- quarantine reads/exports/replays/dispositions for audit.

Avoid tenant labels as high-cardinality metric dimensions; use tenant-scoped
logs/traces and aggregate metrics with privacy-safe dimensions.

### 7.2 Provisional quality gates

The following are proposed acceptance gates, not claims of achieved results.
Re-baseline all metrics on the exact frozen ADR-022 gold revision and pinned
model/runtime before rollout. Evaluate on the same split and report confidence
intervals; do not tune on the hold-out test set.

| Metric | ADR-022 baseline | Proposed gate | Measurement / interpretation |
|---|---:|---:|---|
| Entity F1 | 0.618 | >=0.70 | Frozen gold; no precision collapse. |
| Relation F1 | 0.078 | >=0.15 minimum; >=0.20 stretch | Frozen gold; relation F1 is the primary quality gate. |
| JSONL valid-line rate | Not measured | >=98% | Valid records / non-empty emitted lines, before schema rejection. |
| Truncation recovery | Whole response lost today | 100% complete preceding lines retained | Synthetic truncation fixtures + provider finish reason. |
| Unexplained quarantine | Not measured | <=5% of emitted lines | Excludes expected duplicates; report each reason separately. |
| Subset miss rate | Not measured | <=2% after one widening/full-schema retry | At least 500 schema-valid candidates per release before gating. |
| Cross-boundary relation recall | Not measured | >= baseline full-context boundary recall - 2 percentage points; target >=80% of gold boundary relations | Frozen boundary subset; compare overlap-only and reconciliation pass. |
| Evidence anchoring | Not measured | >=99% exact deterministic anchors for promoted candidates | Gold spans + exact quote anchoring. |
| Coverage | Not measured | 100% for \`KNOWLEDGE_READY\`; otherwise \`PARTIAL\` | Eligible vs processed segment ledger. |
| Cost/latency | Not measured | No production gate until reference profile is pinned; compare against 512-token baseline | GPU-seconds/document, token/call counts, p95 wall clock. |

The 0.70 entity / 0.15 relation thresholds are initial engineering gates to
review against inter-annotator agreement and baseline variance. If the frozen
gold's annotation ceiling makes a target unattainable, record the ceiling and
approve a revised threshold before looking at hold-out results. A phase cannot
pass on average F1 alone if evidence anchoring, coverage, or safety gates fail.

### 7.3 Phase gates

**P0 — JSONL and schema safety**

- JSONL is canonical for every multi-fact extraction path.
- Complete lines survive truncation; malformed lines are quarantined.
- No model offsets/IDs; deterministic anchoring and provenance.
- Legacy Pass-2 reads are disabled for new RAKG jobs; migration plan in §9.

**P1 — ontology contract**

- A pinned static schema artifact is consumed first; compiler/API is added only
  after schema digest and replay tests pass.
- Syntology compilation is deterministic and release-addressed.
- A broken domain/range fixture fails closed.

**P2 — bounded segmentation and scheduling**

- Compare 256/384/512 segments at equal GPU-time/token budgets.
- Segment and call budgets are enforced; 200-chunk fixtures have bounded
  scheduling and explicit partial coverage.
- Cross-boundary relation gate passes with overlap and reconciliation policy.
- No fixed hardware throughput claim until reference GPU profile is recorded.

**P3 — subset and semantic validation**

- Subset recall and miss-rate gates pass; fallback is exercised.
- Batch domain/range + SHACL validation reduces invalid promoted relations.
- No subset rollout if the miss-rate threshold is exceeded.

## 8. Evaluation plan

Use the ADR-022 frozen gold with a pinned source revision, model, tokenizer,
runtime, ontology release, prompt, and validator. Compare 256/384/512-token
segments and 32/48/64-token overlap while holding retrieval chunks at 512 tokens.

**Cross-segment relation experiment**

Create a gold boundary subset where the subject, predicate cue, or object lies
on opposite sides of a segment boundary. Report:

\[
\text{cross-boundary relation recall} =
\frac{\text{gold boundary relations recovered}}
{\text{all gold boundary relations}}
\]

Report precision as well as recall, split by relation type and distance between
supporting spans. Compare (A) overlap only, (B) overlap + deterministic
candidate reconciliation, and (C) overlap + a bounded adjacent-segment
semantic reconciliation pass. The bounded pass may run only when both endpoint
mentions are independently anchored in adjacent segments and the predicate is
allowed by the pinned schema. It receives the two local contexts and candidate
endpoints, not a document-wide summary. No unconstrained coreference inference
is added to the first release. A later coreference pass requires a separate
gold set and ADR. Adopt the semantic pass only if it improves boundary recall
without violating precision/F1 and GPU-budget gates.

**Cost/latency experiment**

For each segment/overlap pair report: exact calls/document, prompt and
completion tokens, GPU-seconds/document, queue wait, wall-clock p50/p95,
throughput, peak KV-cache use, energy/cost where measurable, entity/relation F1,
and coverage. Run on a pinned reference profile: GPU model/VRAM, model
revision/quantization, inference runtime, batch size, concurrency, tokenizer,
and workload mix. Run both isolated-document and multi-tenant contention
scenarios. Do not infer a dollar amount from local GPU use without an explicit
amortization/energy model.

**Ontology subset experiment**

Measure gold class/predicate recall before and after dependency closure,
closure overflow, full-schema fallback frequency, \`OUT_OF_SUBSET\` rate, and
quality impact. Sweep top-k, similarity threshold, and budgets on development
data only. Keep the hold-out test split untouched.

**Line-size experiment**

Measure evidence quote bytes on the gold and synthetic long-table/legal-clause
fixtures, including UTF-8 expansion. Report p50/p95/p99/max and the fraction
above 16 KiB. The acceptance goal is zero gold evidence records rejected by the
transport bound; any exception must be reviewed before production rollout.

**Fixtures and reproducibility**

Include truncation with complete preceding lines, mixed valid/invalid lines,
wrapper-array/prose protocol violations, unknown IDs, Unicode/long evidence,
table row groups, cross-segment relations, subset fallback, ontology release
change, legacy-cache invalidation, source deletion, quarantine access audit,
provider timeout, budget exhaustion, and deterministic replay. Report
confidence intervals and repeat runs to expose model nondeterminism.

## 9. Phased rollout and legacy migration

| Phase | Goal | Main work | Rollback |
|---|---|---|---|
| P0 | Stop losing facts to JSON fragility | JSONL adapter, per-line validation/quarantine, deterministic anchoring | Feature flag routes new jobs to legacy adapter; RAKG remains canonical |
| P1 | Pin ontology contract | Consume static schema artifact, then add Syntology compiler/API | Fall back to last known-good pinned schema artifact, not an unversioned vocabulary |
| P2 | Bound segmentation and scheduling | Smaller segments, budgets, deterministic consolidation, partial coverage | Disable segment splitting for new jobs and return to one 512-token chunk per call |
| P3 | Add ontology subset + batched validation | Selector, fallback, miss-rate feedback, semantic batch validation | Disable subset; use full schema within context budget |

**Legacy Pass-2 migration**

1. Stop creating new legacy Pass-2 cache entries for RAKG jobs. Mark cache rows
   with a legacy namespace/version and make new readers refuse them by default.
2. Add an explicit feature flag for the legacy EnrichStage path. New RAKG jobs
   never invoke Pass 2. Existing in-flight legacy jobs may finish under the old
   path during the migration window, but their results remain tagged
   \`legacy-pass2\` and cannot be silently mixed with RAKG candidate results.
3. Run a shadow comparison on a sampled, isolated corpus: legacy output and
   RAKG JSONL output are stored separately and compared against gold. Shadow
   mode must not dual-write canonical graph facts.
4. Invalidate cache reads by changing the cache namespace/key version; do not
   delete historical rows before the retention period. Reprocess selected source
   versions from persisted chunks under the new schema. Preserve old results
   for audit but mark them non-replayable under the new contract.
5. Roll back by disabling the new feature flag for affected tenants and
   re-enqueueing source versions. Do not route new jobs back through the buggy
   Pass-2 cache path. Trigger rollback if relation F1 falls below 0.078 on two
   consecutive frozen-gold evaluation runs, promoted-anchor accuracy falls
   below 99%, or any tenant-isolation/provenance invariant fails.
6. Remove the legacy Pass-2 code and cache reader only after the dual-run window
   completes, no in-flight jobs remain, and the owner signs off on migration
   metrics. Decommissioning is a separate implementation PR.

**Ontology evolution and replay**

Every candidate and quarantine item pins ontology release, schema digest,
prompt/model/tokenizer/validator revisions. A new ontology release does not
mutate historical results. Revalidate existing candidates against the new
release in a separate replay generation; if class IDs or domain/range semantics
change incompatibly, re-extract affected source versions from persisted chunks.
Quarantined lines may be replayed only after schema/selector remediation and
must retain links to the original disposition. Promotion uses the new generation
only after full validation; never silently rewrite the prior graph generation.

**Partial coverage contract**

Persist eligible, processed, skipped, failed, and pending segment counts plus
coverage ratio and reason codes. Expose \`coverage=PARTIAL\` and these counts
through the extraction result/status consumed by downstream APIs and UI.
Partial candidate sets are queryable for diagnostics but **do not promote by
default** and must not report \`KNOWLEDGE_READY\`. A tenant/domain policy may
explicitly allow partial promotion with a visible coverage annotation and
auditable override. Budget exhaustion or transient failures schedule retry of
only missing segments using idempotent segment keys.

## 10. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Too many logical components | Delivery and operational burden | Implement as modules in existing services; no new deployables or stores without ADR/evidence. |
| Subset selector misses relevant class | Recall loss | Miss-rate telemetry, one widened/full-schema retry, steward review; pause release rollout above threshold. |
| Smaller segments increase calls | GPU saturation and latency | Exact call/token/GPU-seconds benchmark, bounded batching/admission, per-document budgets. |
| Cross-segment relation loss | Relation F1 regression | Gold boundary metric and bounded adjacent-segment reconciliation experiment. |
| Evidence line exceeds byte bound | Legitimate candidate rejected | 16 KiB initial limit, gold byte distribution, one shorter-evidence retry; never byte-truncate JSON. |
| Quarantine contains tenant data | Confidentiality/retention risk | Tenant-key encryption, read audit, 30-day default/90-day max, source deletion cascade. |
| Strict schema rejects valid output | Candidate loss | Explicit quarantine, schema/prompt review; no silent coercion. |
| Ontology release changes | Historical result drift | Immutable release/schema digests; new replay generation and explicit backfill. |
| Partial extraction mistaken for complete | Invalid promotion | PARTIAL is surfaced end-to-end and blocks promotion by default. |
| Model confidence is miscalibrated | False precision | Confidence is informational; validation/anchoring gate acceptance. |
| Provider structured-output differences | Contract fragmentation | Normalize to canonical JSONL at adapter boundary. |

## 11. Open decisions

1. Confirm the ADR-022 gold revision, annotation ceiling, and proposed quality
   gates (entity F1 >=0.70; relation F1 >=0.15 minimum / >=0.20 stretch).
2. Pin the reference GPU/model/runtime profile before setting p95 wall-clock or
   monetary cost targets. Until then, GPU-seconds/document and relative
   256/384/512 comparisons are the acceptance measures.
3. Decide whether the bounded adjacent-segment semantic reconciliation pass
   clears the boundary recall/precision and equal-budget gates.
4. Confirm tenant key-management integration and quarantine retention exceptions
   with the security owner.
5. Confirm the partial-promotion policy; default is no promotion when coverage
   is PARTIAL.
6. Confirm legacy Pass-2 decommission date after the dual-run and rollback
   gates pass.
7. Decide whether a small-model-first tier is justified by measured segment
   complexity and cost. It must use the same JSONL contract, and escalation
   must be based on deterministic routing signals or validated failure codes,
   not raw uncalibrated model confidence.

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
