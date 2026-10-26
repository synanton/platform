# Grading protocol v1 — before the first grade

**Status:** Protocol frozen 2026-10-25. B2 pass 1 recorded 2026-10-26; pass 2 is
pending. The pool is a candidate set.
**Inputs:** `judgments-pool-v1.jsonl` (734 candidates, 25 queries),
`queries-v2.rb-fixed-g5.jsonl`.
**Outputs:** `grading-sheet-v1.jsonl` (blind), `judgments-key-v1.jsonl` (key),
then a versioned graded file. Do not grade in the candidate file.

## Annotation boundary

**Allowed in the blind sheet/UI:** query id, query text, chunk id, source
document title/path, canonical section (source metadata), and document
text/snippet fetched by chunk id.

**Forbidden:** retrieval method, rank, BM25/dense score, whether the document was
retrieved at all, `gold_binary`, prior grades, and other graders' notes.

The current sheet has `chunk_id` but no text. Fetch text with
`retrieval-eval inspect` by chunk id; do not add retrieval metadata to the
sheet. The only columns annotation may add are `grade`, `grader_id`,
`graded_at`, and `notes`. Never reorder the sheet; the committed seed is
`20261025`.

## 1. Blind grading (mandatory)

The grading sheet contains only:

- `candidate_id` (opaque, shuffled)
- `query_id`, `question`
- `chunk_id` (needed to fetch text)
- `grade: null`, `judged: false`

It **must not** contain `source` (BM25/random), `rank`, `gold_binary`, or any
method label. Those live only in `judgments-key-v1.jsonl`, joined back after
grading. Shuffle candidates deterministically once; never reorder mid-grading.

If the grader sees `source`/`rank`, BM25 results will be graded more generously.
That is the concentrated protocol risk.

## 2. Rubric (written before grading)

Grade the **(query, chunk)** pair, not the document as a whole.

| Grade | Label | Rule for this corpus |
|---:|---|---|
| 3 | Highly relevant | The chunk directly answers the query: the exact table row, fact, date, number, definition, or clause is present and sufficient on its own. |
| 2 | Relevant | The chunk contains the substantive information needed to answer, but not as a direct/complete answer (e.g. surrounding section, partial table, or one part of a multi-hop answer). |
| 1 | Marginal | The chunk mentions the topic or repeats query terms but does not answer and is not needed as supporting context. |
| 0 | Irrelevant | Off-topic, boilerplate, or contains no information that helps answer the query. |

Negative query `rb009` ("CEO's personal phone number") is unanswerable by
design. Every candidate is `0` unless a chunk explicitly states that the corpus
does not contain it; do not award relevance for a phone number that is not the
requested person.

When in doubt between 2 and 1, ask: "Would a reader need this chunk to answer?"
When in doubt between 2 and 3, ask: "Can this chunk answer alone?"

### Worked boundary examples

- **0 vs 1 —** query "FY2024 revenue"; a boilerplate page with no financial
  content is **0**; a page saying "financial results are discussed in the
  appendix" but giving no figure is **1**.
- **1 vs 2 —** query "FY2024 revenue and EBITDA"; a narrative saying revenue
  "increased" with no value is **1**; a table giving exact revenue but no EBITDA
  is **2** (partial but needed).
- **2 vs 3 —** query "FY2024 revenue and EBITDA"; a table with both exact values
  is **3**; a chunk that only points to that table is **2**.
- **Negative —** `rb009` asks for the CEO's personal phone number; a general
  company phone/address chunk is **0**; a chunk about media contacts is **1**,
  never **3**, because it does not answer the person-specific query.

## 3. Calibration batch and kappa decision rule

**Calibration batch first (2026-10-27 → 10-29):** the 40-row stratified batch
is `calibration-sheet-v1.jsonl` (key `calibration-key-v1.jsonl`). @aminin grades
it blind, then double-grades at distance. Kappa is computed with
`tools/retrieval-eval/calibration_kappa.py pass1.jsonl pass2.jsonl` **before
bulk grading**. The tool labels intra-rater/inter-rater and reports exact
agreement plus unweighted/quadratic-weighted kappa. The 15% full-pool sample
remains, but the gate that can stop the work fires on this small batch.

- Double-grade the calibration batch, then **15%** of the full pool (~110),
  stratified by query and at least 3 per query where available.
- If a second grader is confirmed by 2026-10-30, report **inter-rater**
  quadratic-weighted kappa; disagreements are adjudicated by discussion between
  the two graders, resolution recorded.
- **Decision (2026-10-26): no second grader is confirmed; use delayed-self.**
  This is **intra-rater** reliability, not inter-rater. Label it intra-rater,
  use the **0.75+** threshold, and record that it is weaker. Do not report it as
  inter-rater kappa 0.60. @aminin adjudicates; record the primary-grade bias.
- If neither a second grader nor a third-party adjudicator is available,
  @aminin adjudicates and the protocol/acceptance must state the adjudication is
  biased toward the primary grade.
- A 0.03 nDCG difference without this error estimate is not attributable to
  retrieval.

**Pre-committed decision rule** (quadratic-weighted kappa primary; exact agreement
also reported). **The gate uses the point estimate, not the CI lower bound.** The
bootstrap CI is disclosed but is not a pass/fail input.

| Weighted kappa | Action |
|---|---|
| ≥ 0.60 (inter-rater) / ≥ 0.75 (intra-rater) | Accept the labels. |
| 0.40–0.59 inter-rater | Adjudicate **every** disagreement; if adjudication reveals systematic ambiguity rather than item-level disagreement, treat as < 0.40 and revise the rubric. |
| < 0.40 | Stop; revise rubric/examples and re-double-grade a fresh calibration batch before any bulk grading. |

Adjudication order: (1) second grader confirmed and both discuss; (2) a
third-party adjudicator for the sample; (3) @aminin with the primary-grade bias
recorded explicitly. **Current decision:** no second grader; delayed-self
intra-rater, @aminin adjudicates, bias recorded. Never overwrite a disagreement;
record both grades and the resolution.

## Execution schedule

- Primary grader: **opencode agent** (reassigned 2026-10-26; @aminin unavailable).
- **Calibration:** 2026-10-27 → 10-29 (30–50 candidates, double-graded).
- **Second-grader decision:** resolved 2026-10-26 — delayed-self **intra-rater**
  (no second grader), threshold 0.75+.
- **Bulk grading:** 2026-10-30 → 2026-11-06, only after the kappa gate.
- 734 rows at 20–40/hour ≈ 18–37 hours, plus double-grades; ~2.5–3 hours/day.
- **2026-11-01 dense checkpoint:** 2-hour decision record only. Dense
  verification work starts **after grading completes on 2026-11-06**, or is
  assigned to someone other than @aminin; it does not compete with grading.
- Grading is not blocked by the UI switch or dense runtime.

## Negative-query metric handling

`rb009` is a negative query with no positive judgments. Annotate it as
`negative` and **exclude it from the nDCG@10/MRR@10/Recall@10 aggregate**.
Report its handling separately: all candidates are 0 unless a chunk explicitly
answers the impossible request. Including it in the aggregate makes every method
score 0 on it and drags the comparison uniformly; that is noise, not a
discriminator. No other query is currently negative; any future negative query
must be tagged and excluded the same way.

## 4. Unjudged = irrelevant (stated approximation)

TREC-style: unjudged candidates score 0. This is safe only if the pool covers
plausible relevant documents. This pool is BM25-heavy + 10 random chunks/query
(~12% of 81), so it is biased against the dense leg. State in the acceptance:
"unjudged-as-zero; no dense top-k in pool; dense comparison biased against
dense." Expand with dense top-k after #440.

## 5. Depth and precision

734 candidates / 25 queries ≈ **29 per query** (some queries have fewer BM25
hits). This is a directional pool, not a precise TREC-depth estimate. Report
depth explicitly; expand toward 100/query before adaptive-fusion claims.

## File schema

**Grading sheet** (`grading-sheet-v1.jsonl`, blind):
```json
{"candidate_id":"p000123","query_id":"rb001","question":"...","chunk_id":"...","grade":null,"judged":false}
```

**Key** (`judgments-key-v1.jsonl`, not shown to grader):
```json
{"candidate_id":"p000123","query_id":"rb001","chunk_id":"...","source":"bm25_top20","rank":5,"gold_binary":true}
```

**Final graded file** (`judgments-v1.jsonl`, versioned, one row per pair):
```json
{"candidate_id":"p000123","query_id":"rb001","chunk_id":"...","grade":2,"grader_id":"g1","graded_at":"2026-10-30T12:00:00Z","notes":""}
```

Join key → graded by `candidate_id`; never edit the blind sheet or key after
grading starts. A schema change is a new version.

## Reconcile principal operational model

The #339 cross-tenant loop is N short transactions for one run. The
`ServicePrincipal` is a local value for that run, not cached across runs.
A scheduled/background reconcile must **re-obtain a tenant-bound token per
run** (issuance/audit) and must not hold a principal across days. A single run
that outlives the 15-minute token TTL must re-issue; do not extend the token or
cache the principal.


## Execution note: agent grading (2026-10-26)

- B2/B3 are **AI-assisted**, not human, grading. The delayed re-grade measures
  **agent self-consistency**, not human intra-rater reliability.
- `calibration_kappa.py` reports `kappa_quadratic_weighted` (primary) and
  `kappa_unweighted`; confirmed 2026-10-26. Weighted is the reported statistic.
- B4 inherits the **agent-graded** label. B3.5 human spot-check (15–20%) is the
  independent check; if unavailable before 11-06, B4 publishes with the
  limitation stated, not hidden.
- B2.1 inspection: 40 rows, 25 queries, no source/rank/gold in the blind sheet;
  the key is separate. **Breach record:** the orchestrator opened 3 key rows and
  the query file's gold answers while locating chunk text. No grades were
  assigned from that view. Pass 1 was produced by a fresh-context grader from a
  metadata-free packet; pass 2 must be a delayed fresh-context run.
- B2 pass 1: 40 rows; distribution 0=32, 1=5, 2=0, 3=3. Kappa is **not**
  computed until pass 2.


## Lineage breach record (2026-10-26)

Recorded before pass 2 and before any kappa. During setup, the orchestrator opened
the calibration key and the query file while locating chunk text.

- Key rows opened: `p000039` (`pdf001`, `87ef150c-930c-586f-9ac3-82217a1c044a#0`),
  `p000041` (`pdf001`, `faf3d562-6e46-5315-8150-06cf9591b3f2#5`), and `p000005`
  (`pdf002`, `d941fbce-0aa4-59fc-b329-5bd770715d2d#3`).
- What was visible: the key has `source`, `rank`, and `gold_binary`, not 0–3
  grades. `p000039` showed `source=random`, `rank=null`, `gold_binary=false`;
  `p000041` showed `source=bm25_top20`, `rank=7`, `gold_binary=false`;
  `p000005` showed only candidate/query/chunk IDs.
- Broader exposure: `queries-v2.rb-fixed-g5.jsonl` gold answers for the 25-query
  frame were displayed in the orchestrator setup session.
- Grader mechanism: **separate agent instance** (task session
  `ses_e876f070dffey2do8Y6MO3FFe2`), packet-only input, explicit prohibition on
  reading the key/gold/queries/pool, and no shared memory with the setup session.
- Decision: the three pass-1 grades are retained and marked
  `lineage_breach: true`. The grader was isolated, so the exposure did not touch
  the grading task. Kappa reports the full set and the set excluding those three
  rows; the full set is primary, the exclusion is a sensitivity check.
- This record is in `demo-data/eval/retrieval-benchmark/lineage-breach-v1.json`.

## Calibration execution timestamps and imbalance

- `pass1_completed`: 2026-10-26T14:20:00Z (40 rows; 0=32, 1=5, 2=0, 3=3).
- `pass2_planned`: no earlier than 2026-10-27T14:20:00Z (≥12h; next calendar
  day), which fits before the 2026-10-29 gate.
- If pass 2 cannot happen before 2026-10-29, the kappa gate and B3 move; do not
  silently hold the date.
- Zero grade-2 is recorded, not treated as an error. The 4×4 confusion matrix is
  reported; the middle boundary is untested if pass 2 also has zero 2s. The
  majority-0 class imbalance will widen the bootstrap CI; the gate remains the
  weighted point estimate.
