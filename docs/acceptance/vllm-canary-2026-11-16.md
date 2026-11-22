# vLLM extraction bounded canary — 2026-11-16

**Status:** controlled canary complete; vLLM is a viable provider, not the default.
**Run:** `canary-1794821462`
**Tenant:** `demo` only
**Final provider:** `regex` (rollback path restored); vLLM Ready 1/1 for the next rollout decision.

## Configuration

| Item | Value |
|---|---|
| Model | `qwen3-extraction` (Qwen3-4B-Instruct-2507 on the node3 hostPath) |
| vLLM | `local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b` |
| Prompt | `extraction-v1-aligned` (#719): active ontology types, predicates, and relation contracts |
| Ontology | `demo/1.0.0` |
| Synflux | `local-registry:5000/synanton/synflux@sha256:05018ff59efb505aeff0bcff4c5826b4239d28bbb6db291dec82ac93f0be7343` |
| Canary provider | `llm` |
| Fail-closed flag | `SYNQUEST_SEARCH_VISIBILITY_FAIL_OPEN_UNMAPPED=false` (unchanged) |

The canary used controlled, small documents with the existing demo canonical
entities. It measures extraction and graph outcome on the resolver's real
boundary; it is not a real-world corpus.

## Documents

| # | Document | Type | Expected relations | Notes |
|---|---|---|---|---|
| 1 | `quarterly-report.pdf` | PDF | 0 | negative control; no candidates |
| 2 | `syn2-requires.epub` | EPUB | 1 | Halcyon-7 requires Auxiliary Coolant Loop |
| 3 | `syn2-tested.epub` | EPUB | 1 | Relief Valve tested every Ninety Days |
| 4 | `syn2-requires-a.txt` | TXT | 1 | same as #2 |
| 5 | `syn2-requires-b.txt` | TXT | 1 | same as #2 (repeatability) |
| 6 | `syn2-tested-a.txt` | TXT | 1 | same as #3 |
| 7 | `syn2-tested-b.txt` | TXT | 1 | same as #3 (repeatability) |
| 8 | `syn2-replaced-a.txt` | TXT | 1 | Relief Valve replaced every Two Hundred Seventy Days |
| 9 | `syn2-records-a.txt` | TXT | 1 | Every Test records Ambient Dew Point |
| 10 | `syn2-glossary.txt` | TXT | 0 | negative control; entities but no genuine relations |

## Per-document metrics

| # | Document | Mentions | Normalized | Candidates | Accepted | Rejected | New canonical | New supports | Duplicate supports | Promotion | Converged |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | quarterly-report.pdf | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | none | yes |
| 2 | syn2-requires.epub | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 3 | syn2-tested.epub | 2 | 2 | 1 | 1 | 0 | 1 | 1 | 0 | COMPLETED | yes |
| 4 | syn2-requires-a.txt | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 5 | syn2-requires-b.txt | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 6 | syn2-tested-a.txt | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 7 | syn2-tested-b.txt | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 8 | syn2-replaced-a.txt | 2 | 2 | 1 | 1 | 0 | 0 | 1 | 0 | COMPLETED | yes |
| 9 | syn2-records-a.txt | 3 | 3 | 1 | 1 | 0 | 1 | 1 | 0 | COMPLETED | yes |
| 10 | syn2-glossary.txt | 7 | 7 | 4 | 0 | 4 | 0 | 0 | 0 | none | yes |

## Aggregate

- 24 entity mentions, 24 normalized entities.
- 12 relation candidates: 8 accepted, 4 rejected (66.7% acceptance).
- 2 new canonical relations:
  - `entity-relief-valve --tested_every--> entity-ninety-days` (`aa24bec6...`)
  - `entity-every-test --records--> entity-ambient-dew-point` (`fb39795f...`)
- 8 new `SUPPORTS/CURRENT` evidence rows; 0 duplicate supports; 8/8 positive
  promotion rows `COMPLETED`; 0 failed.
- Tenant ACTIVE `:REL` count: 3 before -> 5 after (two additions; no losses).
- Lifecycle regressions: 0. No `CONFLICTED`/`UNSUPPORTED` introduced; the #655
  relation stayed ACTIVE.

## Gate 1 — extraction quality

| Metric | Result | Pass condition |
|---|---|---|
| Entity precision (reviewed sample) | 23/24 = 95.8% | >=80% |
| Entity recall (expected sample) | 24/24 = 100% | >=70% |
| Relation acceptance rate | 8/12 = 66.7% | >=50% |
| Negative control accepted relations | 0 | 0 |
| Reproducibility | requires 3x, tested 3x, halcyon 3x identical | identical normalized sets |

The sample review covers the 8 positive documents plus the glossary. The one
entity-type error is `Halcyon-7` typed `Organization` in the glossary instead of
`MaintenanceSystem`.

## Gate 2 — graph integrity

| Check | Result |
|---|---|
| New canonical relations vs new evidence | 2 canonical relations; 8 support evidence rows |
| Lifecycle regressions | 0 |
| ACTIVE count before/after | 3 -> 5 (no net loss) |
| Promotion convergence | 8/8 positive rows COMPLETED; negatives 0 rows |
| False graph facts from negative controls | 0 accepted |
| CONFLICTED/UNSUPPORTED introduced | none |

## Gate 3 — operational

- Provider attribution: all 10 canary extraction completions logged
  `engine=LlmExtractionAdapter`.
- Convergence: every document reached `CURRENT`; no pending promotion rows.
- Failure recovery: no canary document failed; retries were not needed.
- Rollback exercised during the canary:
  - `engine=RegexExtractorAdapter` after flipping to regex (source
    `canary-rollback-regex-1794822246`).
  - `engine=LlmExtractionAdapter` after flipping back (source
    `canary-rollback-llm-...`).
  - Final provider restored to `regex` with one env change; no image rebuild.

## Negative control detail

`quarterly-report.pdf`: 0 expected relations, LLM proposed 0, accepted 0.

`syn2-glossary.txt`: 0 expected relations, LLM proposed 4, accepted 0. The four
candidates were all rejected by the ontology type contracts:

| Candidate | Rejection layer |
|---|---|
| Auxiliary Coolant Loop (Component) --requires--> Relief Valve (Component) | domain mismatch (requires needs MaintenanceSystem) |
| Every Test (Concept) --tested_every--> Ninety Days (Time) | domain mismatch (tested_every needs Component) |
| Every Test (Concept) --replaced_every--> Two Hundred Seventy Days (Time) | domain mismatch (replaced_every needs Component) |
| Ambient Dew Point (Measurement) --records--> Every Test (Concept) | domain/range mismatch (records needs Concept -> Measurement) |

This is a false-positive signal at candidate level. The validation boundary
protected the graph: no false relation was committed.

## #720 observation

The dedicated `syn2-records-a.txt` document accepted the correct
`Every Test --records--> Ambient Dew Point` relation. The glossary negative
control produced the misattributed `Ambient Dew Point --records--> Every Test`
candidate, which was rejected. The defect recurred in 1/10 canary documents
(10%): below the 20% blocker threshold, but above the 5% known-limitation
threshold. Keep #720 open and monitor.

## Quality assessment vs regex

- This canary did not rerun regex on the same controlled corpus. The LLM-vs-regex
  comparison remains the #718 halcyon result: regex 0 relations, LLM 2 accepted
  relations with active evidence.
- On this controlled corpus, the LLM recovered all 8 expected positive relations,
  added 2 new canonical relations and 8 supports, and introduced no lifecycle
  regressions.
- The negative control shows the LLM can invent plausible but invalid relation
  candidates; ontology validation rejects them. Graph precision is protected,
  but candidate precision needs monitoring.
- The controlled, small-document set with existing canonical entities does not
  establish general superiority over regex.

## What this does not prove

- Real-world documents, high volume, or cross-tenant behavior.
- Non-English content or complex ontology types beyond the demo maintenance and
  supply-chain vocabulary.
- New-entity resolution: the initial supply-chain synthetic set with new entities
  stalled at `MENTIONS_COMMITTED`; the canary therefore used existing canonical
  entities.
- Large PDF throughput: two large PDFs were excluded after extraction proved too
  slow for the bounded run.
- That `regex` is no longer needed; it remains the rollback and default provider.

## Decision

The controlled canary passes the extraction, graph-integrity, and operational
gates. vLLM is a viable provider for a bounded canary. It is not the default:
broad rollout remains gated on #713, and the #720 candidate/attribution issue
remains observed and open. The stable provider is `regex`.
