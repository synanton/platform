# Prompt/response evidence and error attribution — IT Books extraction

**Status:** Analysis, documentation-only
**Historical baseline:** #919 (`f087ce6f…`, entity F1 0.632, relation F1 0.000)
**New extraction runs:** none
**Protected state:** A2, IT Books v1, CSO, #919 artifacts, graph, and official metrics unchanged

## 1. Evidence inventory

### 1.1 Available artifacts

| Artifact | Exists | Content |
|---|---|---|
| `run1-results.json` / `run2-results.json` | yes | predictions, metrics, evidence binding, unsupported counts |
| `run-manifest.json` | yes | model, P0 IDs/digests, prompt version, decoding, hashes, run summaries |
| `REPORT.md` | yes | narrative findings and limitations |
| `AUTHORIZATION.md` / `authorization.json` | yes | scope and boundaries |
| `SHA256SUMS` | yes | results/manifest hashes; does not cover the capture directory |
| Two parse-failure captures | yes | raw generated text/usage/reason for the malformed chunk; no prompt |
| Successful raw prompts/responses | **no** | not persisted |
| Per-attempt request/execution IDs | **no** | returned in memory, not serialized |
| Prompt digest | **no** | absent from #919 manifest |
| Contract digest | **no** | #919 predates post-#923 hydration metadata |
| Retrieval context | **no** | no retrieval implementation |
| CSO context | **no** | CSO is not read by extraction |

### 1.2 Pinned identities

- #919 result hash: `f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`
- Run manifest hash: `8147c5e167d6c0767eb7bb7859401074d9ffae6320a0c826daa1fe80a341887b`
- P0 release: `47fe38f8-9f72-481b-826a-6c5b12a6027a`
- P0 release digest: `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c`
- P0 SPEC digest: `8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455`
- P0 template digest: `333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b`
- P0 compilation ID: `cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b`
- Model: `synanton-qwen3-4b-synthesis`; manifest revision `qwen3-4b-instruct-2507/vllm-openai:v0.29.0`
- Prompt in #919 manifest: `extraction-v1`
- Gateway: `synanton.gpu.v1/SYNTHESIZE`; no direct vLLM/TEI; no fallback

### 1.3 What the historical evidence can and cannot prove

It can prove:

- the exact A2 fixture and P0 identities;
- aggregate emitted types/predicates and unsupported counts;
- aggregate evidence binding outcomes;
- the malformed chunk, its usage, finish reason, and raw generated text;
- the two runs' byte-identical parsed results.

It cannot prove:

- the exact successful prompts or system/user messages;
- what ontology context the model actually saw beyond the prompt construction code;
- the per-request Gateway IDs;
- whether the model omitted an entity because of missing definitions, retrieval failure, model
  capability, or prompt ambiguity;
- the prompt digest or contract digest for the historical run.

Therefore no claim below uses #919 as a post-#923 E0 control.

## 2. Current prompt construction and confirmed gaps

### 2.1 Prompt structure

When a compiled contract is supplied, the system prompt is built from the selected template
(`extraction-v1` or `extraction-v2`) with:

- allowed type IDs;
- allowed predicate IDs;
- expanded relation endpoint lines;
- appended compiled-contract identity (`releaseId`, `releaseDigest`, `compilerVersion`,
  `schemaVersion`, `contractDigest`);
- the chunk text in the user message.

No entity labels, relation labels, descriptions, aliases, examples, retrieved concepts, or CSO
topics are rendered. The user message is only `TEXT:\n<chunk>`.

### 2.2 Confirmed code-level gaps

| Gap | Evidence | Impact |
|---|---|---|
| Evaluation harness does not pass compiled contract to adapter | `GpuSemanticEvaluation.java:94-95` constructs the adapter without `contract` | A current E0 P0-contract prompt is not wired; adapter falls back to context types + default predicates |
| Prompt version identity mismatch | adapter constant `extraction-v2` (`LlmExtractionAdapter.java:77`); evaluation uses `extraction-v1`; provenance hard-codes `PROMPT_VERSION` (`:264-265`) | Provenance would name `extraction-v2` even when v1 was used |
| Contract digest is incomplete for prompt identity | `P0ContractLoader` hashes canonical SPEC + template only | Does not cover prompt template text, hydrated prompt, chunk, model, decoding, or retrieval |
| Loader does not compare report digests to artifact bytes | only releaseId equality is checked | Drift/inconsistency can pass unless digests are independently verified |
| Loader does not enforce reference integrity | no check that relation endpoints exist as entity IDs; empty lists accepted | Invalid contract can load and prompt |
| Entity default can hide omission | blank `proposedType` becomes `Concept` (`mapEntity`) | A missing type is silently converted rather than surfaced |
| `UNKNOWN` can be introduced by relation endpoint resolution | `resolve(...)` adds `UNKNOWN` entity (`LlmExtractionAdapter.java:553`) | Unsupported type can appear even without a model entity line |
| Gateway raw body is generated text, not full envelope | `LlmCallResult.rawBody` gets `CompletionResponse.text` | Request/execution identity and full provider metadata are lost from capture |
| Successful interactions are not captured | only parse-failure/quarantine writes | Cannot audit successful prompt/response pairs |

These are observations; no code changes are included in this PR.

## 3. #919 observations

### 3.1 Aggregate results

- 22 A2 mentions; 17 relations (8 accepted / 8 rejected / 1 unjudged).
- 16 predicted resolved mentions.
- Entity TP/FP/FN: 12 / 4 / 10; precision 0.750; recall 0.545; F1 0.632.
- 8 predicted relations; TP/FP/FN: 0 / 8 / 8; relation F1 0.000.
- 16/16 entity and 8/8 relation evidence spans resolved in `STRUCTURAL_CHUNK`.
- One malformed chunk per run; fail-closed; not converted into negative judgments.
- 0 quarantined lines in the parsed result; unsupported terms appear as aggregate counters.

### 3.2 Unsupported output

- Unsupported type: `UNKNOWN` (1).
- Emitted relation predicates: `hassupplier` (2), `partoforder` (3), `records` (1), `requires` (2).
- None matched the eight expected predicates, so relation TP is zero even though all relation
  evidence bound.

### 3.3 Malformed generation

The two tracked captures describe the same malformed Think Python chunk:

- finish reason: `stop`;
- generated text ends with an invalid `charEnd:10` token (missing quote/colon structure);
- raw/content/parser-input SHA-256: `8f6023226fabee2878c98d1c48b7aadbe9c4c4cf2a062e1039e528f3c64651e7`;
- prompt tokens 491, completion tokens 33;
- parser exception: `expected ':' at 97`;
- no prompt, request ID, execution ID, contract digest, or prompt digest retained.

This is a **formatting failure with a success finish reason**, not truncation. The parser correctly
failed closed. The absence of the prompt means the cause (model formatting, prompt ambiguity, or
decoding behavior) cannot be confirmed.

## 4. Error attribution framework

Every observed error should carry a stage, a status, and an evidence reference.

| Stage | Attribution | Status in #919 |
|---|---|---|
| Ontology coverage gap | concept/relation absent from P0 | Not established |
| Ontology description/label ambiguity | no definitions/aliases | Confirmed absent; impact hypothesis |
| Concept retrieval failure | no retrieval exists | Not applicable; capability absent |
| Prompt construction failure | contract not wired; version identity mismatch | Confirmed code gap; historical effect unknown |
| Model semantic misunderstanding | wrong entity/relation choice | Hypothesis; needs successful prompt/response |
| Unsupported term generation | `UNKNOWN`, lower-case/variant predicates | Confirmed output; cause hypothesis |
| Domain/range violation | endpoint type mismatch | Not separately observable in #919 aggregate; post-#923 code classifies it |
| Formatting/parsing failure | malformed JSON | Confirmed; exact prompt unavailable |
| Evidence grounding failure | P3a unresolved/misbound | None in #919 (16/16, 8/8 resolved) |
| Gateway/infrastructure failure | timeout/non-success | Not observed in #919; one failed chunk was malformed output |

### 4.1 Distinguish confirmed from hypothesis

- **Confirmed:** output counters, evidence binding, malformed text, missing successful prompts,
  missing retrieval, prompt/version wiring gaps.
- **Hypothesis:** why the model emitted variant predicates; why 10 entities were missed; whether
  definitions or retrieval would change quality; whether relation F1 zero is predominantly a
  prompt-vocabulary or model-semantic issue.

Do not convert hypotheses into causes without a captured successful interaction.

## 5. Diagnostic metrics

These are diagnostics, not official evaluation metrics. They must not change #919 denominators.

| Metric | Definition |
|---|---|
| Ontology context coverage | fraction of chunk concepts/types with supplied definition or selected context |
| Retrieved concept relevance | judged precision of selected concepts at k, with pinned judge |
| Unsupported term rate | unsupported type/predicate candidates / all candidates |
| Domain/range violation rate | DOMAIN_RANGE quarantines / relation candidates |
| Entity/relation omission rate | missed A2 items / A2 items, reported separately from negative judgments |
| Prompt/completion tokens | per attempt and aggregate, from Gateway usage |
| Evidence binding success | RESOLVED / candidates, by space/grounding |
| Malformed/incomplete generation | malformed or non-stop responses / attempts |
| Error distribution | attributed stage counts / all errors |

## 6. Controlled experiment proposal

**Do not execute.** This requires separate authorization and the E0 wiring fix.

### 6.1 Profiles

| Profile | Prompt content | Purpose |
|---|---|---|
| E0 | Current post-#923 P0 contract-only prompt, with contract actually passed to the adapter | Establish the true hydrated control |
| E1 | E0 + relevant ontology definitions and alternative labels | Isolate definitions/labels |
| E2 | E0 + retrieved concepts and bounded graph context | Isolate retrieval, with bounded context |

CSO is a separate advisory-context experiment. Do not add CSO to E0/E1/E2.

### 6.2 Controls

Pin all of:

- model `synanton-qwen3-4b-synthesis` and deployment revision;
- A2 `it-books-a2-v1` and annotations;
- decoding (`temperature=0`, `maxTokens=4096`, same timeout);
- prompt base template and P0 contract;
- evidence-binding contract `p3a-evidence-binding-v1`;
- evaluation metric definitions and denominators;
- two runs per profile for repeatability only, not a determinism claim.

### 6.3 Hypotheses and expected diagnostics

| ID | Hypothesis | Expected if supported |
|---|---|---|
| H1 | Definitions/aliases reduce type ambiguity and variant predicates | fewer `UNKNOWN`/unsupported terms; higher entity precision; relation predicate adherence improves |
| H2 | Definitions/aliases do not solve missing concepts | omission may persist; coverage metric rises without recall necessarily rising |
| H3 | Bounded retrieval improves relevant concept coverage | higher context coverage/relevance; recall may rise; leakage/tokens may rise |
| H4 | Unbounded or poorly scoped retrieval hurts precision | more unsupported terms or irrelevant relations; token growth |
| H5 | Evidence binding is independent of semantic correctness | high RESOLVED can coexist with low relation F1 (observed in #919) |

### 6.4 Expected outcomes to record

- entity/relation TP/FP/FN and official F1 unchanged in definition;
- unsupported/domain-range quarantine counts;
- prompt/completion tokens and latency;
- malformed/incomplete rate;
- per-stage attribution distribution;
- context coverage/relevance and prompt-context digest;
- exact prompt/response artifact links.

## 7. Recommendations

### Before any experiment

1. Pass the compiled contract into the evaluation adapter, or explicitly label runs as
   non-hydrated.
2. Fix prompt version provenance so the adapter records the resolved template version.
3. Add a prompt-input digest covering template + hydrated values + contract + chunk identity.
4. Add artifact capture for successful requests/responses behind the approved security model.
5. Add loader consistency checks (artifact bytes vs report digests; endpoint references;
   non-empty contract).
6. Separate `UNKNOWN` entity resolution from candidate emission so unsupported output is visible.
7. Validate the P0 definitions/aliases gap; do not mutate published P0 for an experiment.

### Prompt/context

8. Add definitions/aliases as an explicit E1 overlay.
9. Design retrieval with explainable selection, bounds, and a reproducible context digest.
10. Keep CSO advisory and isolated.

### Governance

11. Keep raw prompts/responses out of Loki.
12. Require tenant isolation, access audit, encryption, retention, and deletion before production.
13. Do not treat this analysis as an evaluation or quality claim.

## 8. Security and retention

- The current captures contain fixture text and generated text; they are tracked in the repo.
- Production capture must use separate protected storage, not the repository or Loki.
- Do not record authorization headers, tokens, keys, or unrelated tenant data.
- Define retention/deletion and access audit before enabling successful-response capture.
- Keep artifact references and digests in logs; keep content in the protected store.

## 9. Evidence references

- `evaluation/it-books/p3b-p4/gateway-eval-v1/run-manifest.json`
- `evaluation/it-books/p3b-p4/gateway-eval-v1/run1-results.json`
- `evaluation/it-books/p3b-p4/gateway-eval-v1/REPORT.md`
- `evaluation/it-books/p3b-p4/gateway-eval-v1/capture/it-books-eval/it-books-a2-v1/`
- `java/extraction-engine/src/main/java/org/synanton/extraction/engine/llm/LlmExtractionAdapter.java`
- `java/extraction-engine/src/main/java/org/synanton/extraction/engine/contract/P0ContractLoader.java`
- `java/synflux/src/test/java/org/synanton/synflux/evaluation/GpuSemanticEvaluation.java`
- `docs/implementation/it-books/p0/it-books.spec.json`
- `docs/implementation/it-books/p0/it-books.template.json`
- `docs/implementation/it-books/p0/it-books.report.json`
