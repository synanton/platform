# Bounded two-stage extraction pilot — registration only

**Status:** REGISTERED, NOT EXECUTED. No inference or spend authorized.
**Purpose:** test whether the merged boundary chunker plus two-stage schema/endpoint
validation removes truncation and schema failures without repeating the 32K pilot.

## Frozen inputs

- Model: `deepseek-v4-pro`, provider `opencode`, external-only.
- Prompt: `evaluation/prompts/llm_extraction_system.txt`
  (`92ab28a86ce777e792dbe0177b3c9bc50e55bb388416aae8b464d20ed69cd382`).
- Schema: entity types and predicates from `extraction_pipeline.py`; structured
  output capability must be verified live.
- Selection: `evaluation/dev25/experiments/bounded-twostage-selection.tsv`
  (6 previously failing source chunks + 3 controls, split into 35 bounded
  subchunks using `plan_boundary_chunks` with 3,000-char target, 200 overlap).
- Max output: **16,384 tokens per subchunk**; no 32K-as-is rerun.
- Temperature 0; thinking disabled / reasoning low; no fallback model.

## Two-stage contract

1. **Stage 1:** extract entities from each subchunk; enforce allowed types,
   exact offsets/surface matches, and resolution status.
2. **Stage 2:** extract relationships only over accepted Stage 1 mentions;
   enforce allowed predicates, direction, endpoints and evidence spans.
3. Reject unsupported endpoints/predicates/evidence; never repair a relation
   into a pass.
4. Merge subchunks deterministically by document offsets; deduplicate by span;
   preserve chunk ID and source provenance.
5. Cross-chunk links remain explicit candidates, not invented relations.

## Provider preflight

Before every call and every batch:

- verify the live provider's advertised output limit and structured-output
  capability;
- record the limit source;
- run `preflight_output_budget` against every subchunk;
- fail closed if the limit is missing, below 16,384, or any subchunk exceeds it.

## Execution shape

- Maximum **3 documents per batch**.
- At most one request per subchunk per stage; no automatic retries beyond one
  transport retry, and all attempts recorded.
- Stop after the 9-document pilot; no expansion to other failed chunks.

## Cost estimate

- 35 subchunks; estimated input 20,007 tokens; worst-case output 35 × 16,384.
- At live prices $1.74/$3.48 per million: **$2.00**; with 20% retry allowance
  **$2.40**.
- Proposed hard cap: **$2.50**; not authorized. Current budget remains untouched.

## Acceptance criteria

| Metric | Target |
|---|---|
| `finish_reason=length` | 0 |
| JSON validity | 100% |
| Schema validity | 100% |
| Control regressions | 0 |
| Endpoint/evidence validity | No degradation |
| Relation metrics | Report separately; no unsupported-triple inflation |
| Spend | ≤ $2.50 |

## Stop conditions

- provider limit/capability not verified;
- any preflight failure;
- any control regression;
- any schema failure or length termination;
- spend reaches $2.50;
- HOLDOUT/production access.

## Authorization boundary

This proposal authorizes nothing. No model calls, spend, HOLDOUT access,
production change, acceptance, ranking, or auto-merge.
