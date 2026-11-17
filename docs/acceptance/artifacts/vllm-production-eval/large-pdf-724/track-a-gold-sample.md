# Track A gold-sample annotation

**Baseline:** `track-a-candidates.tsv` — all 564 original regex candidates for
`source_version_id=e8aa6942-9d1a-5568-a9da-5f233d1aa03a`, with stable candidate
IDs, predicate, surfaces, proposed types, chunk, and evidence offsets.
**Sample:** `track-a-gold-sample.tsv` — 30 stratified candidates. Label columns
are intentionally `UNREVIEWED`; no automated values are treated as gold.

## Required labels

For each row, set:

| Column | Values | Meaning |
|---|---|---|
| `subject_valid` | `YES`, `NO`, `UNCERTAIN` | Is the subject a meaningful entity in context? |
| `object_valid` | `YES`, `NO`, `UNCERTAIN` | Is the object a meaningful entity in context? |
| `subject_type_approved` | type or `NONE` | Approved type; use `NONE` if the subject is invalid |
| `object_type_approved` | type or `NONE` | Approved type; use `NONE` if the object is invalid |
| `predicate_correct` | `YES`, `NO`, `UNCERTAIN` | Does the predicate express the evidence? |
| `evidence_sufficient` | `YES`, `NO`, `UNCERTAIN` | Do the offsets/chunk contain enough support? |
| `relation_valid` | `YES`, `NO`, `UNCERTAIN` | Should this relation be accepted as canonical knowledge? |
| `review_status` | `REVIEWED` | Set only after all labels are complete |
| `review_notes` | free text | Brief rationale; cite the evidence span |

Use the source chunk (`chunk_ordinal` + `evidence_start`/`evidence_end`) to
inspect the evidence. The original extraction output must not be edited; label
in the sample file only.

## Selection

The sample is intentionally stratified across `is_a`, `has`, `includes`,
`uses`, `part_of`, `measures`, `requires`, and the long tail. It includes
plausible entity pairs and obvious fragments. This is a seed for human review,
not a completed gold set.

## Evaluation after review

Once labelled, report subject/object validity, type accuracy, predicate
accuracy, evidence sufficiency, relation precision, and recall against this
sample. Candidate-filtering changes must be compared with the frozen 564-row
baseline.
