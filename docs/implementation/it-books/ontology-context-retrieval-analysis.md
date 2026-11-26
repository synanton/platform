# Ontology context retrieval analysis — IT Books extraction

**Status:** Analysis, documentation-only
**Evidence:** pinned P0 artifacts and #919 historical artifacts
**New extraction runs:** none

## 1. Bottom line

When a contract is supplied, the extraction prompt receives a **closed extraction contract**,
not an ontology retrieval context. The current `GpuSemanticEvaluation` harness does not yet
supply that contract to its adapter, so it still builds a context-type + default-predicate
prompt. There is no retrieval context in either path. There is no concept selection, description injection, alias expansion,
graph-neighborhood retrieval, or CSO advisory context in the extraction path.

That distinction matters:

- Hydration makes the allowed vocabulary and endpoint constraints explicit.
- It does not give the model enough ontology meaning to distinguish similar concepts.
- It does not retrieve the concepts relevant to a document/chunk.
- It does not make the model's choices explainable at the ontology-concept level.

The current model input is therefore best described as:

> P0-derived vocabulary + endpoint expansion + release identity + raw chunk text.

## 2. What reaches the model

### 2.1 Extraction contract

`P0ContractLoader` loads the immutable P0 SPEC/template/report. When the adapter is constructed
with the compiled contract, `LlmExtractionAdapter.buildPrompt` uses it to substitute:

| Input | Current content |
|---|---|
| Allowed entity types | 11 entity IDs (`Book`, `Author`, `Chapter`, `Section`, `Algorithm`, `DataStructure`, `Concept`, `Database`, `DistributedSystem`, `Topic`, `Definition`) |
| Allowed predicates | 8 predicate IDs (`AUTHORED_BY`, `HAS_CHAPTER`, `HAS_SECTION`, `COVERS_TOPIC`, `DEFINES`, `USES`, `DEPENDS_ON`, `PART_OF`) |
| Relation contract | 74 expanded `subject --PREDICATE--> object` lines |
| Release identity | release ID/digest, compiler version, schema version, export profile |
| Contract identity | `contractDigest` over canonical SPEC + template |
| Output requirement | JSONL records for extraction-v2, or legacy JSON for extraction-v1 |
| Chunk text | appended as the user message `TEXT:\n<chunk>` |

The contract supplies IDs and endpoint expansions. It does **not** render entity labels,
relation labels, relation descriptions, examples, or evidence requirements beyond the generic
prompt rules. The current evaluation harness loads the contract for manifest metadata but does
not pass it to the adapter (`GpuSemanticEvaluation.java:94-95`), so an E0 run is not yet
reachable without a wiring fix.

### 2.2 Ontology definitions

The P0 SPEC has no descriptions or aliases for any entity or relation:

- 0 entities with non-null `description` or `aliases`;
- 0 relations with a non-null `description`.

So even if the prompt rendered every P0 field, there are no canonical definitions or
alternative labels to render. Concept disambiguation currently depends on the model's general
knowledge and the surface text, not on ontology semantics.

### 2.3 Concept retrieval

There is no concept retrieval implementation in the IT Books extraction path.

- `ExtractionContext` carries tenant/document/allowed-type/source-version only.
- `GpuSemanticEvaluation` passes P0 extraction types and builds chunks; it does not retrieve
  concepts, aliases, or graph facts.
- No lexical, embedding, graph, or manual concept selector is invoked.
- No CSO artifact or CSO topic is read by the extraction adapter.
- No retrieval trace, candidate list, relevance score, or selected context is persisted.

### 2.4 Structural evidence

The model gets the chunk text. Downstream, P3a gets the structural document, chunk ID, offsets,
template digest, and compilation ID. The structural artifact is not supplied to the model as
context; it is used after generation for evidence binding.

## 3. What the model can and cannot infer

| Question | Current answer |
|---|---|
| Are allowed types/predicates clear? | Mostly yes: IDs and endpoint expansions are explicit |
| Are canonical definitions available? | No |
| Are alternative labels/aliases available? | No |
| Are relation meanings explained? | Only by predicate ID and endpoint types; no description |
| Are relevant concepts selected per chunk? | No |
| Is the whole ontology supplied? | No; only the 11/8 extraction contract |
| Is retrieval explainable/reproducible? | Not applicable; no retrieval |
| Is unrelated ontology context leaked? | No ontology context is supplied |
| Is CSO used as advisory context? | No |
| Could CSO topic relations be mistaken for technical predicates? | No in the current path; CSO is not read. That risk starts only if CSO context is added without an isolation rule |

## 4. Consequences for extraction quality

### 4.1 Sufficient for vocabulary adherence, not semantic disambiguation

The prompt is sufficient to tell the model which labels are legal. It is not sufficient to
explain what the ontology means by `Concept`, `Topic`, `Definition`, `DataStructure`, or
`DistributedSystem`. The #919 evidence is consistent with this: predicates were emitted in
non-contract casing/variants, and one `UNKNOWN` type appeared, while evidence spans still bound.

### 4.2 The prompt can over-constrain and under-explain simultaneously

The 74 endpoint lines are a strong grammar. They do not explain why a `Chapter` is not a
`Section`, or when a `Concept` is better represented as a `Topic`. Adding more constraint lines
without definitions does not necessarily improve semantic choices.

### 4.3 Missing context is not the same as missing capability

A missing entity/relation can be caused by:

- ontology coverage gap;
- missing definition/alias;
- no concept retrieval;
- prompt ambiguity;
- model semantic miss;
- parser/validator rejection;
- evidence binding failure.

The current record cannot distinguish these after the fact because successful prompts and raw
responses are not retained.

## 5. Retrieval design space

Any future retrieval layer must be explicit about what it retrieves and why.

| Strategy | Candidate source | Reproducibility | Main risk |
|---|---|---|---|
| Lexical/BM25 | ontology labels, aliases, definitions | high if index version pinned | vocabulary mismatch; false positives |
| Embedding | ontology concept embeddings + chunk embedding | requires pinned embedding model/index | model/index drift; opaque relevance |
| Graph | bounded neighbors from syntactic relations | high if seed and hop limits pinned | context explosion; unrelated leakage |
| Manual/curated | per-domain concept pack | highest | does not scale; curation cost |
| CSO advisory | CSO topics + definitions | high if CSO artifact pinned | topic relations mistaken for IT predicates |

A safe retrieval record must include the query, strategy/version, candidate IDs, scores,
selection rule, hop/budget limits, and selected context digest. A retrieved context that cannot
be reconstructed is not auditable.

## 6. CSO boundary

CSO 3.5 is not part of the current extraction prompt or contract. If introduced later:

- CSO is advisory topic context only;
- CSO `superTopicOf`, `relatedEquivalent`, `contributesTo`, and `preferentialEquivalent` must
  never be emitted as IT technical predicates;
- CSO concepts must not silently become A2/IT Books canonical entities;
- the CSO artifact, topic selection, and prompt insertion must be pinned and recorded separately;
- CSO experiment inputs/results must remain unchanged until a separately authorized experiment.

The CSO experiment and the P0 definition/retrieval experiment are distinct variables. Do not
combine them in one comparison.

## 7. Findings

| Finding | Confidence | Basis |
|---|---|---|
| No ontology concept retrieval exists | Confirmed | code path and context shape |
| No descriptions/aliases exist in P0 SPEC | Confirmed | artifact inspection |
| Relation labels/descriptions are not rendered | Confirmed | prompt builder inspection |
| CSO is not used by extraction | Confirmed | no CSO read in extraction path |
| Hydration is contract loading, not retrieval | Confirmed | `CompiledExtractionContract` and `buildPrompt` |
| Evaluation harness does not pass the contract to the adapter | Confirmed | `GpuSemanticEvaluation.java:94-95` |
| Missing definitions may reduce disambiguation | Hypothesis | no direct interaction evidence with definitions |
| Retrieval may improve recall but add leakage | Hypothesis | general retrieval risk; untested here |
| Full ontology injection would be harmful | Hypothesis | no experiment; context/token risk is plausible |

## 8. Required controlled experiment

Do not execute as part of this analysis. See
`prompt-response-error-analysis.md` for hypotheses, variables, and metrics.

Three profiles must be compared with one pinned model, A2 fixture, decoding parameters, and
evaluation methodology:

- **E0:** current post-#923 P0 contract-only prompt.
- **E1:** E0 + relevant ontology definitions and alternative labels.
- **E2:** E0 + retrieved concepts and bounded graph context.

#919 is historical pre-hydration evidence and is **not** an E0 run. It must not be used as the
control for a post-#923 prompt experiment without explicit normalization.

## 9. Recommendation

1. Fix prompt/version identity before any experiment.
2. Add definitions/aliases to an experiment-only ontology overlay; do not modify published P0.
3. Implement retrieval behind a pinned, explainable selection record.
4. Keep CSO separate.
5. Capture successful prompts/responses in a protected artifact store before claiming
   attribution.
6. Do not treat P3a `RESOLVED` as semantic correctness; it proves evidence binding, not meaning.

All implementation and evaluation remain pending explicit authorization.
