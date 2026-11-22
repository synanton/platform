# OGCE JSONL vertical slice — T9 write-up

**Status:** spike result — contract robustness only; no production claim.
**Date:** 2026-11-22
**Decision context:** [ADR-044](../docs/architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Authorization:** [#814](https://github.com/synanton/platform/issues/814), owner `andreminin`; standing dev/testing authorization recorded 2026-11-22.
**Run device:** node2 RTX 4060 Ti, UUID `GPU-a004ce09-4df3-3098-a6dc-1d11175ecf30`, 16 GB. node3 production was not disturbed.
**Evidence:** [`results/`](./results/), [`results/variance/`](./results/variance/), [`results/report.md`](./results/report.md), [`results/raw_responses.jsonl`](./results/raw_responses.jsonl).

## 1. What was tested

The slice tested the **JSONL line contract and extraction-window planner**, not extraction quality. The primary document is a synthetic 2,936-token review dossier with 18 headings, 28 list items, and explicit Person/Organization/Product facts. The model was `Qwen/Qwen3-8B-FP8` revision `220b46e3b2180893580a4454f21f22d3ebb187d3`, FP8 `e4m3`, block size 128.

| Component | Version / value |
|---|---|
| Runtime image | `local-registry:5000/vllm-openai:v0.29.0` |
| vLLM / PyTorch / transformers / Python | `0.29.0` / `2.13.0+cu130` / `5.16.1` / `3.12.3` |
| Driver / CUDA UMD | `615.71.09` / `13.4` |
| Model load | 8.9 GiB in 2.93 s |
| KV cache | 3.11 GiB; 22,640 tokens; 2.76× concurrency at 8,192 |
| Engine init | 39.65 s (31.47 s compilation) |
| Peak observed GPU use | 12,601 MiB (12,580 MiB process) |

**Postflight:** the disposable node2 pod was deleted. Production vLLM remains 1/1 Running on node3 with the same image and `8192/4` args; Synflux remains 2/2 with `EXTRACTION_PROVIDER=regex`. No production configuration was changed.

**Scope deviation:** node2 is an Ada RTX 4060 Ti, not the pinned Blackwell RTX 5060 Ti. This run proves the contract on a free 16 GB device; it does **not** qualify the target profile.

## 2. Provenance and contamination control

- `evaluation/gold/doc-1.json`–`doc-5.json` is the **ADR-022 frozen 5-document gold**. It is reserved for P2-3 and was not used for slice development, prompt tuning, E6, or latency evidence.
- `evaluation/dev25/g7-dev-006..025` is a separate 20-document DEV set. Only its **document texts** were used for the multi-document variance runs.
- The DEV-25 annotation status is contradictory: `evaluation/dev25/README.md` says the 20 are `UNLABELED`; `gold-release.md` says all 25 are annotated/released; `manifest.tsv` marks all 25 `VALIDATED_GOLD`. Because of that conflict, **no DEV labels were consumed**, and **E6 gold-span alignment is deferred** until the status is reconciled.
- **F1 is not measured.** Spike modules lack subset selection, consolidation, and the full validation ladder. F1 on this code would not be gate evidence and would misstate the design. Quality measurement belongs to P2-3 on production modules against held-out gold.

## 3. T3/T4 — document and chunking

| Metric | Result |
|---|---|
| Document | 15,305 chars; **2,936 tokens**; 18 headings; 28 list items |
| Retrieval chunks | 6 |
| Extraction segments | **12** |
| Chunk/segment ratio | **2.0×** |
| Segment tokens | `263, 285, 263, 285, 270, 279, 265, 286, 261, 285, 261, 181` |
| Overlaps | `43, 40, 38, 44, 48, 42, 44, 48, 43, 36, 41, 0` |
| Boundary violations | **0** |
| Repeat-format check | equal (15 lines both runs) |

**Segment size histogram**

| Band | Count |
|---|---|
| 128–191 | 1 |
| 256–319 | 11 |

**Undersized tail inventory**

- Segment 11: **181 tokens**, reason `MERGE_WOULD_EXCEED_384`. Merging it into the previous 285-token segment would exceed the 384-token ceiling, so it is kept and flagged rather than silently padded or merged across a chunk boundary.

**List handling observation**

| List | Items | Chunks | Segments | Atomic? |
|---|---|---|---|---|
| Pilot metrics | 10 | 1 | 1 | yes (one chunk and one segment) |
| Audit checklist | 12 | 2 | 2 | **no — split across chunks 3–4 and segments 7–8** |
| Scorecard actions | 6 | 1 | 2 | **no — split across segments 10–11** |

The spike chunker is sentence-based, not list-aware. Atomic-list preservation is a P1-1 finding: two of three lists split mid-list even when the list stayed inside one chunk. This is a design gap, not a token-size necessity.

## 4. T2 — runtime capability matrix

All probes returned HTTP 200. “Enforced” means the output matched the requested constraint, not merely that the request was accepted.

| Probe | Observed behavior |
|---|---|
| `response_format: json_object` | Single JSON object; enforced. |
| `response_format: json_schema` | Single JSON object; schema/value checks passed; enforced. |
| `response_format: json_schema` + `strict` | Same; enforced. |
| `guided_json` | **Accepted but ignored** — output remained JSONL. |
| `guided_regex` | **Accepted but ignored** — output remained JSONL. |
| `guided_grammar` | **Accepted but ignored** — output remained JSONL. |
| `structured_outputs.json` | Single JSON object; enforced. |
| `structured_outputs.regex` | Regex matched, but produced three concatenated objects with **no newline**; not JSONL. |
| `structured_outputs.grammar` | Exact grammar object enforced. |
| `structured_outputs.grammar` with `root ::= line "\n" line` | **Two newline-delimited objects; enforced** — Tier 2 JSONL grammar is feasible. |
| `stop` | Accepted. |
| `logit_bias` | Accepted; effect not observable in this probe. |
| seed repeat | Identical output on repeat. |

**Key finding:** vLLM 0.29 ignores the legacy top-level `guided_*` fields on this build but honors `structured_outputs`. A generated JSONL grammar under `structured_outputs.grammar` is the viable Tier 2 path. `response_format` is single-object only and must not be presented as JSONL support.

## 5. T6/T7 — calls and validation

| Metric | Result |
|---|---|
| Calls | 12 |
| Lines returned | 164 |
| Lines accepted | 128 |
| Lines quarantined | 36 |
| JSON parse failures | 1 |
| Code-fenced responses | 0 |
| Quarantine reasons | `AMBIGUOUS_ANCHOR`: 14; `SURFACE_NOT_IN_EVIDENCE`: 11; `CLASS_MISMATCH`: 5; `UNANCHORED`: 5; `JSON_PARSE`: 1 |

The contract held: every non-blank line was parsed independently; the single malformed line was quarantined without affecting its neighbours. The 22% quarantine rate is a **model-adherence finding**, not a parser failure. The model reused evidence that appears twice in a segment, cited evidence that did not contain the claimed surface, or chose the wrong class. Prompt-only mode needs few-shot examples and/or a stricter unique-evidence instruction before production promotion; lowering the validator would hide the problem.

## 6. T8 — proof experiments (expanded document)

| ID | Claim | Result | Evidence |
|---|---|---|---|
| E1 | C2 truncation | **PASS** | At 25/50/75% line truncation, JSONL retained 3/3, 7/7, 10/10 pre-cut accepted facts (100%). Monolithic JSON retained 0 at all three points. Headline at 50%: 7/7 vs 0. |
| E2 | C3 malformed line | **PASS** | Clean 15-line response; corrupting the 3rd accepted line newly quarantined only that line; the other 14 accepted lines were unaffected. |
| E3 | C4 anchoring | **PASS** | 15 accepted lines before and after shuffle; anchors identical; anchors do not depend on line position. |
| E4 | C5 coverage | **PASS** | Forced skip: 12 eligible, 11 processed, 1 skipped, `PARTIAL`; full pass: `COMPLETE`; counts for returned/accepted/quarantined present; no silent regex/dictionary default. |
| E5 | C1 boundaries | **PASS** | 12 segments across 6 chunks; zero segment spans outside their parent chunk. |

## 7. Variance addendum

### Primary document — n=10 latency

| Metric | Result |
|---|---|
| Segment | 1 (285 tokens) |
| Calls | 10 |
| p50 / p95 | **18.2517 s / 18.2585 s** |
| min / max | 18.2459 s / 18.2585 s |
| Unique output hashes | 1 |
| Format signatures equal | yes |

This is an exploratory single-segment distribution. It is **not** G-10 evidence.

### Seeds 42/43/44

All three seeds produced the same output hash (`dc4ff1a8…`) and the same format signature. This supports order/seed stability on this exact segment; it does not establish bitwise determinism across hardware or runtime versions.

### Tier 2 JSONL grammar

`structured_outputs.grammar` with `root ::= line "\n" line` produced exactly two newline-delimited objects. **Enforced: true.** This is the strongest runtime result in the slice.

### Five DEV documents (text only; no labels)

| Doc | Stratum | Tokens | Chunks | Segments | Accepted | Quarantined | E1 | E2 | E3 | E5 | p50 | p95 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `g7-dev-011` | relationship_heavy | 327 | 1 | 1 | 3 | 3 | PASS | PASS | PASS | PASS | 4.90 s | 7.91 s |
| `g7-dev-013` | relationship_heavy | 521 | 2 | 3 | 7 | 4 | PASS | PASS | PASS | PASS | 3.65 s | 10.13 s |
| `g7-dev-016` | cross_document_references | 334 | 1 | 1 | 3 | 0 | PASS | PASS | PASS | PASS | 4.90 s | 5.01 s |
| `g7-dev-018` | cross_document_references | 498 | 1 | 2 | 6 | 8 | PASS | PASS | PASS | PASS | 8.28 s | 15.14 s |
| `g7-dev-023` | tables_structured | 66 | 1 | 1 | 6 | 0 | PASS | PASS | PASS | PASS | 6.04 s | 6.06 s |

Aggregate exploratory latency over 34 calls: **p50 8.1994 s, p95 18.2551 s**. The primary n=10 dominates the upper tail. These are not G-10 evidence.

**E7 coverage:** three strata were exercised with five documents. The `entity_dense_technical`, `ambiguous_or_contradictory`, and `ontology_definition` strata were not exercised because the frozen 3-class/2-relation slice schema does not match their label vocabularies, and the five `ontology_definition` documents are the reserved ADR-022 gold. This is a deliberate scope boundary, not an oversight.

**E9 coverage:** 5 DEV documents × 3 passes plus the primary n=10. This is a representative subset, not the full 25-document × 3 matrix. The full E9 remains P2-3/P1 work on the real modules.

## 8. Recommendation

**Promote the chunker, line parser, validation ladder, and coverage report into P0-1/P0-3/P1-1**, with these hardening items:

1. **Add a JSONL grammar generator + cache** using `structured_outputs.grammar`; cache on schema/model/revision/backend/tokenizer/grammar-engine digests (R2.2–R2.3). This is the only observed mechanism that enforces line-delimited output.
2. **Do not use `response_format: json_schema` as the JSONL mechanism.** It enforces a single object. Keep it as the non-JSONL fallback.
3. **Treat `guided_*` as unsupported on this build.** Record it in the runtime capability matrix; do not silently rely on it.
4. **Harden the prompt and anchor policy.** The 22% anchor/class quarantine needs few-shot examples, a unique-evidence instruction, and a review of repeated boilerplate within segments. Do not relax the quarantine rule.
5. **Add list-aware segmentation in P1-1.** Two of three lists split mid-list; atomic-list preservation is not implemented by the spike chunker.
6. **Keep the short-tail rule explicit.** Flag undersized tails with `segment_undersized` and a reason; do not pad or merge across chunk boundaries.
7. **Re-run on the pinned Blackwell RTX 5060 Ti** only through a separately approved isolated window. The Ada result is contract evidence, not profile qualification.

## 9. What this does not prove

- No extraction-quality, precision/recall, F1, cost, throughput, or p95/G-10 claim.
- No pinning or validation of the Blackwell RTX 5060 Ti profile.
- No ontology subset selection, cross-boundary relation recall, candidate consolidation, migration, retention, or quarantine implementation.
- No DEV-label or frozen-gold consumption; E6 is deferred.
- No ADR-044 status transition; no gate is marked **Met** or **Missed**.

## 10. Reproduce

The run is reproducible from [`run_slice.py`](./run_slice.py), [`run_variance.py`](./run_variance.py), [`fixtures/sample-doc.md`](./fixtures/sample-doc.md), and [`fixtures/dev/`](./fixtures/dev/) against an OpenAI-compatible vLLM endpoint with the model mounted at `/models`. The pod spec is [`pod.yaml`](./pod.yaml). Raw model responses are in [`results/raw_responses.jsonl`](./results/raw_responses.jsonl); they are synthetic or DEV text and contain no production data.
