# P0 compiler → Gateway extraction traceability

**Basis:** IT Books v1 release `47fe38f8…` / digest `3a4a5edf…`; P0 artifacts under
`docs/implementation/it-books/p0/`; PR #919 gateway path; baseline
`f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`.
**Status:** review and proposal; no compiler or prompt contract changed.

## Matrix

| Contract concern | Ontology release | P0 SPEC/template | Gateway prompt | Response validation | Status |
|---|---|---|---|---|---|
| Entity type definitions | A1 classes | `entities[]` / `entityTargets[]` | `{allowed_types}` + relation text | `ExtractionContext.allowedEntityTypes`; unknown types quarantined | Partial: prompt types are passed by the evaluation harness, not hydrated from the compiled SPEC artifact. |
| Canonical identifiers | Class IDs / IRIs | `id`, `type`, `superTypes` | Not sent | Evaluation matches type/surface/offset | Gap: model never receives canonical IRIs. |
| Alternative labels | A1 labels only | Not present | Not present | No alias matching | Gap: no synonym/alias contract. |
| Predicate definitions | A1 relation labels | `relations[].label` | Hard-coded `DEFAULT_PREDICATES` / `RELATION_CONTRACT` in `LlmExtractionAdapter` | Parser allows hard-coded predicate set; unsupported predicates quarantined | High risk: prompt and parser are not generated from the published P0 relation contract. |
| Domain/range constraints | A1 marker-expanded types | `subjects[]` / `objects[]` | Hard-coded relation contract | Relation predicate set only; domain/range not enforced at model boundary | High risk: unsupported or semantically wrong relations can be proposed and only fail downstream. |
| Required evidence format | N/A | N/A | char offsets + `evidenceQuote` requested | JSONL parser; P3a binder validates offsets | Partial: offsets are prompt-local; P3a adds space/geometry semantics after extraction. |
| Unsupported-term handling | N/A | Coverage report | Instruction not to invent | Unknown type/predicate quarantined; raw output captured | Partial: unsupported proposals are visible, but no typed `UNSUPPORTED_PREDICATE` metric in the evaluation report. |
| Imported ontology provenance | Not present | Not present | Not present | Not present | Gap for a future CSO/domain-ontology profile. |
| Release/compiler identity | Release ID/digest | Provenance + digests | Not in system prompt | Run manifest pins identity | Partial: provenance is outside the model context; model cannot see the exact release identity. |
| Model/prompt identity | N/A | N/A | `extraction-v1`; prompt digest in provenance | Manifest records prompt/model | Partial: `finish_reason` is not carried through the gateway client; see gateway review. |
| Malformed/incomplete output | N/A | N/A | N/A | JSONL/legacy parse; `ExtractionIntegrity` | Partial: one malformed chunk in #919 was rejected; finish_reason loss can mask truncation. |

## Findings and recommendations

1. **Hydrate the prompt from P0 output.** Build allowed types, predicates, labels, and
   subject/object expansions from the compiled SPEC/template, not hard-coded constants.
2. **Add an explicit prompt-context artifact.** Include release ID/digest, compiler version,
   schema version, and the relation contract digest. This also gives CSO an additive,
   provenance-bearing context field without changing A1.
3. **Do not treat CSO topic predicates as IT predicates.** If CSO is added, send it as
   advisory concept/label context with an explicit prohibition on topic-relation inference.
4. **Make unsupported proposals first-class.** Record unsupported predicates/types and
   domain/range violations separately in evaluation; never map them to an allowed predicate.
5. **Fix the gateway finish-reason/provenance gap before any contract extension.** Otherwise
   added ontology context could increase truncation risk without being visible.
6. **Require canonical IDs in a future extraction schema.** At minimum include the mapped
   IT class ID and CSO/imported concept ID in a distinct provenance field; do not overload
   `surfaceForm`.

No P0, P1, P3a, A2, or IT Books v1 artifact was modified by this review.
