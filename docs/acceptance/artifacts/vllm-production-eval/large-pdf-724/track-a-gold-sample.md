# Track A gold-sample annotation

**Baseline:** `track-a-candidates.tsv` — all 564 original regex candidates for
`source_version_id=e8aa6942-9d1a-5568-a9da-5f233d1aa03a`, with stable candidate
IDs, predicate, surfaces, proposed types, chunk, and evidence offsets.
**Sample:** `track-a-gold-sample.tsv` — 30 stratified candidates split into
development and holdout rows. All labels remain `UNREVIEWED`; no automated
values are treated as gold.
**Evidence export:** `track-a-gold-sample-evidence.tsv` — the extracted chunk
text, page range, token count, candidate fields, and evidence substring for all
30 sampled chunks. This is the extraction output, not PDF page-text reconstruction.

## Split policy

- `DEV` rows may be used to inspect errors and design filtering/typing rules.
- `HOLDOUT` rows must not be used to tune the rules; use them only for the
  post-change evaluation.
- With 30 rows, report counts and uncertainty, not only percentages.

## Required labels

Label subject and object validity independently from their types. Use
`AMBIGUOUS` when the surface may be a valid entity but context is unclear.

| Column | Values | Meaning |
|---|---|---|
| `subject_valid` | `YES`, `NO`, `AMBIGUOUS` | Is the subject a meaningful entity in context? |
| `object_valid` | `YES`, `NO`, `AMBIGUOUS` | Is the object a meaningful entity in context? |
| `subject_type_approved` | type or `NONE` | Approved type; `NONE` if invalid |
| `object_type_approved` | type or `NONE` | Approved type; `NONE` if invalid |
| `predicate_correct` | `YES`, `NO`, `AMBIGUOUS` | Does the predicate express the evidence? |
| `evidence_sufficient` | `YES`, `NO`, `INSUFFICIENT_EVIDENCE` | Do the offsets/chunk contain enough support? |
| `relation_valid` | `YES`, `NO`, `AMBIGUOUS` | Should the relation be accepted as canonical knowledge? |
| `proposed_subject_correction` | free text | Optional corrected span/type; separate from the validity decision |
| `proposed_object_correction` | free text | Optional corrected span/type; separate from the validity decision |
| `proposed_predicate_correction` | free text | Optional canonical predicate; separate from the validity decision |
| `review_status` | `UNREVIEWED`, `REVIEWED` | Set `REVIEWED` only when all labels are complete |
| `review_notes` | free text | Brief rationale; cite the evidence span |

Use the source chunk (`chunk_ordinal` + `evidence_start`/`evidence_end`) to
inspect the evidence. Do not edit the original extraction output; label only in
this sample file.

## Selection

The sample is stratified across `is_a`, `has`, `includes`, `uses`, `part_of`,
`measures`, `requires`, and the long tail. It includes plausible entity pairs
and obvious fragments. This is a seed for human review, not a completed gold
set.

## Evaluation after review

Report subject/object validity, type accuracy, predicate accuracy, evidence
sufficiency, relation precision, and recall against the held-out rows. Compare
candidate-filtering changes with the frozen 564-row baseline; never tune on the
holdout rows.
