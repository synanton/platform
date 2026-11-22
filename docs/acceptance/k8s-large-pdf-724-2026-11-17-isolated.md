# #724 isolated large-PDF runs — 2026-11-17

**Document:** Google 2023 Environmental Report
**SHA-256:** `4af4c647a6b309c75967590bda80404b15355744d7d678a205facc6901e89b80`
**Pages:** 104 | **Words:** 53,119 | **Tenant:** `eval-smoke`
**Historical runs:** recorded separately in
`k8s-large-pdf-724-2026-11-17.md`; this report does not mix those numbers.

## Isolation record

| Field | Regex pass | LLM pass |
|---|---|---|
| Upload run ID | `82f1b484-c2f0-368d-9e99-7a4a6a265cea` | `f9b07f81-6311-3b72-bf86-5da0c2a79652` |
| Source version | `1dc72097-fe03-549e-9e90-c5f07bb757ff` | `dcae9d42-5ee5-5ec6-8b03-7187b54b9568` |
| Resource | `...benchmark-regex.pdf` | `...benchmark-llm.pdf` |
| Persisted provider | `regex` | `llm` |
| Terminal status | `KNOWLEDGE_READY` / graph `GRAPH_COMMITTED` | `EXTRACTION_FAILED`, dead-lettered |

Both passes used the same PDF bytes but distinct source versions, run IDs, and
persisted providers. The content-addressed `content_ref_id` is shared by
identical bytes; extraction-result rows are keyed by content ref, so they are
not storage-isolated between passes. The regex event and source-version row were
captured before the LLM run; the LLM failure did not alter the regex source
version's terminal fields. No regex source-version writes were made after its
capture. The global provider config was restored to `regex` after the LLM pass.

## Regex pass

| Metric | Value |
|---|---:|
| Total duration | 371,651 ms (6m 11.7s) |
| Extraction | 673 ms |
| Graph | 370,977 ms |
| Indexing | 159,249 ms |
| Peak heap observed (both synflux pods) | 186,558,328 bytes (~177.9 MiB) |
| Entity mentions | 11,799 |
| Normalized entities | 3,105 |
| Relation candidates | 564 |
| Relations accepted | 0 |
| Relations rejected | 564 |
| Processing outcome | `PARTIAL` |
| HTTP 500s / stage timeouts | none |
| GPU | not used |

Rejection reasons: 538 `unknown_predicate`, 16 `unknown_subject_type`,
10 `domain_mismatch`. Predicate aggregates: `is_a` 406, `has` 62,
`includes` 29, `uses` 21, `part_of` 16, `measures` 11, `requires` 10, long tail 9.

Timing semantics: `duration_ms` is wall-clock from extraction start to graph
commit. `graph` is 370,977 ms and dominates. `indexing` is the observed
`search_projected_at - requested_at` wall-clock delta; it overlaps the run and is
not additive to `duration_ms`. Extraction was 673 ms. The extraction event
reported `PARTIAL`; the source row at capture showed `GRAPH_COMMITTED`, with 0
accepted relations. Source activation generation 8 was `CURRENT`; source_current
active revision was `1dc72097...`.

## LLM pass

The LLM pass did **not complete**. At the current 2,048 output-token cap, the
adapter classified the response as incomplete before parsing:

```text
INCOMPLETE_LLM_OUTPUT finish_reason=length
prompt_tokens=804 completion_tokens=2048 total_tokens=2852
response_chars=8864 response_sha256=e0b24d4c74371b3e8aea7abc2aa3f813cf61710dae82f5fe797bf768efe63212
```

| Metric | Value |
|---|---|
| Total / extraction / graph / indexing | not produced |
| Entity mentions / normalized / candidates | not produced |
| Accepted / rejected relations | not produced |
| Attribution errors (#720) | not measurable |
| Terminal status | `EXTRACTION_FAILED`, `extraction_event_dead_lettered=true` |
| HTTP 500s / stage timeouts | none; incomplete-output classification |
| GPU memory observed | ~12,975 / 16,311 MiB; current observation, not a peak series |
| Heap observation | shared benchmark-window max ~177.9 MiB; not isolated by run |

The failure is output-generation truncation, not input-context exhaustion: the
same chunk at 4,096 tokens returned `finish_reason=stop` and valid JSON in the
earlier controlled reproduction. The 4,096 setting remains an experimental
candidate, not a production default. No boundary correction, ontology change, or
promotion relaxation was made.

## Comparison and decision

There is no completed LLM metric set to compare with the regex run. Regex
finished with 0 accepted relations (a pre-existing quality finding), and LLM
terminated at the output cap. Therefore:

- #724 is **not closed** by this run.
- Pre-flight remains **10/11 operational**; do not start #723.
- The regex default remains unchanged; production has two INFO synflux replicas.
- The next engineering decision, if pursued, is a separately reviewed
  experimental output-cap configuration (4,096) and a fresh isolated LLM run.
- HOLDOUT was not opened or used.
