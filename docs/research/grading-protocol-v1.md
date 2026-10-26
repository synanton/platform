# Grading protocol v1 — before the first grade

**Status:** Protocol frozen 2026-10-25. The pool is a candidate set; no grades
exist yet.
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

## 3. Inter-rater sample and kappa decision rule

- Double-grade **15%** of the pool (~110 candidates), stratified by query and
  including at least 3 candidates per query where available.
- Use a second grader where possible; otherwise the same grader at least 7 days
  later without access to the first grades.
- Report exact agreement % and Cohen's kappa (unweighted and quadratic-weighted
  for the 0–3 scale).
- A 0.03 nDCG difference without this error estimate is not attributable to
  retrieval.

**Pre-committed decision rule** (quadratic-weighted kappa primary; exact agreement
also reported):

| Weighted kappa | Action |
|---|---|
| ≥ 0.60 | Accept the labels. |
| 0.40–0.59 | Adjudicate **every** disagreement; record both grades and the adjudicated grade; recompute kappa. |
| < 0.40 | Stop; revise rubric/examples and re-double-grade a fresh sample before continuing. |

Adjudicator: @aminin. Second grader: to be named by 2026-10-30. If none is named,
use a delayed same-grader pass (≥7 days) and state that the estimate is weaker;
do not overwrite disagreements—record them.

## Execution schedule

- Primary grader: **@aminin**.
- Start: **2026-10-27**. Target: **2026-11-06**.
- 734 rows at 20–40/hour ≈ 18–37 hours, plus ~110-row double-grade ≈ 3–6 hours.
  Plan ~2.5–3 hours/day.
- The 2026-11-01 dense-runtime decision is a 2-hour checkpoint; no full benchmark
  run that day. Grading is **not** blocked by UI switch or dense runtime; start
  it in parallel.

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
