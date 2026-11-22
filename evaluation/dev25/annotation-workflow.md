# AI-assisted annotation workflow (preparation only)

AI assistance is not yet authorized. When separately authorized, this workflow
applies to the 20 UNLABELED documents. AI output is candidate suggestions only.

1. Extract text from each source document (PDFs are not yet extracted; a
   deterministic text-extraction step must be registered before annotation).
2. Generate candidate entities, relationships, types, direction, and evidence
   spans using a non-candidate annotator model.
3. Validate each candidate against the annotation schema (`validate.py`).
4. Attach source evidence to every proposed annotation.
5. Flag unsupported claims, ambiguity, contradictions, and unresolved references.
6. Present suggestions to a human annotator.
7. Require human accept / correct / reject for every candidate.
8. Adjudicate disagreements and uncertain cases (`adjudication/records.tsv`).
9. Export only human-approved, adjudicated annotations into `gold/`.

AI proposals are stored separately from final human annotations and are never
imported into `gold/` directly. Candidate evaluation outputs must not be used
as annotation suggestions for the same evaluation set.
