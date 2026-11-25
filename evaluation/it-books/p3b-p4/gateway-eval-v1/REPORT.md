# P3b/P4 gateway extraction evaluation — report

**Run ID:** `it-books-gateway-evaluation-v1`
**Status:** COMPLETE · small-fixture evaluation, not a production-accuracy claim
**Authorization:** `AUTHORIZATION.md` / `authorization.json`
**A2:** `it-books-a2-v1` · **P3a:** `p3a-evidence-binding-v1`
**Gateway:** `synanton.gpu.v1/SYNTHESIZE`
**Model:** `synanton-qwen3-4b-synthesis` (LOCAL vLLM) · **Prompt:** `extraction-v1`

## Results (run 1 = run 2, byte-identical results)

| Metric | Result |
|---|---:|
| A2 mentions | 22 |
| A2 relations | 17 (8 accepted / 8 rejected / 1 unjudged) |
| Predicted resolved mentions | 16 |
| Entity TP / FP / FN | 12 / 4 / 10 |
| Entity precision / recall / F1 | 0.750 / 0.545 / 0.632 |
| Predicted relations | 8 |
| Relation TP / FP / FN | 0 / 8 / 8 |
| Relation precision / recall / F1 | 0.000 / 0.000 / 0.000 |
| Rejected A2 relations predicted | 0 |
| Unjudged A2 relations predicted | 0 |
| Unknown relations | 8 |
| Evidence binding (entities / relations) | 16 / 16 and 8 / 8 RESOLVED |
| UNMAPPED / MISBOUND | 0 / 0 |
| Malformed chunks | 1 per run; rejected fail-closed |
| Quarantined lines | 0 |
| Runtime | 26,706 ms / 26,173 ms |
| Tokens | 4,728 prompt / 1,195 completion per run |

**Reproducibility:** both runs produced byte-identical `results.json`
(`f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`). LLM
nondeterminism remains possible; this two-run result is not a guarantee.

## Findings

- Every emitted evidence span resolved through P3a in `STRUCTURAL_CHUNK` space:
  16/16 entities and 8/8 relations. No document-scope fallback.
- One Think Python chunk returned malformed JSON (missing quote after `charEnd`).
  The adapter rejected it fail-closed; raw capture is under `capture/`. The chunk
  was not silently dropped or converted into negative judgments.
- Unsupported emitted type: `UNKNOWN` (1). Emitted relation predicates were
  `hassupplier` (2), `partoforder` (3), `records` (1), `requires` (2); none are
  among the eight A1 predicates, so relation TP is zero.
- Missing output was not treated as a negative judgment. A2 rejected and
  unjudged cases remain distinct and unmodified.
- A2 is a small Markdown excerpt fixture; geometry is `NOT_APPLICABLE`. This run
  makes no PDF bbox claim. PDF geometry must use the P1 PDF fixture.

## Environment and gateway

- Gateway was restored and verified with a live SYNTHESIZE call.
- Gateway image: `local-registry:5000/gpu-gateway:0.1.0@sha256:8a747eb2c2d77bb7a39d3cb4400746183939d5899829c410c69a67c668e8ef13`
- The Gateway routes SYNTHESIZE to `vllm-synthesis`; `vllm-extraction` was scaled
  to 0 for this evaluation. The dev TLS secret was rotated because the deployed
  server certificate had expired.
- No direct vLLM/TEI call from platform application code; no fallback; no graph
  writes; no canonical assignment; no production/customer index writes.

## Artifacts

`run1-results.json`, `run2-results.json`, `run-manifest.json`, `capture/`,
`authorization.json`, `AUTHORIZATION.md`, `SHA256SUMS`.

## Limitations

- Small, independently annotated fixture; no broad extraction-quality claim.
- Relation output did not use the A1 predicate vocabulary; relation metrics are
  therefore zero even though the relation evidence bound correctly.
- The model was the Gateway's synthesis endpoint (`synanton-qwen3-4b-synthesis`);
  it is not a dedicated extraction model. The one malformed response is reported.
- The first-run/second-run result hash equality is evidence of repeatability for
  this pinned configuration, not proof of deterministic LLM inference.
