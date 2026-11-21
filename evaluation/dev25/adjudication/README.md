# Adjudication records

One row per disagreement or uncertain annotation in `records.tsv`. Both original
annotations are preserved; the resolution is recorded without deleting them.

Columns: `doc_id`, `category`, `annotator_1`, `annotator_2`,
`disagreement_type`, `resolution`, `adjudicator`, `adjudication_date`, `notes`.

No rows are expected until human annotation begins for the 20 unlabeled
documents. This directory is currently a template only.
