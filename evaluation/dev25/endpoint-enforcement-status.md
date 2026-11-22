# Accepted-endpoint enforcement status

**Status:** offline implementation; no inference or spend.

- Relation endpoints must resolve to exactly one accepted mention.
- Explicit reasons: `ENDPOINT_ONLY_IN_ONTOLOGY`,
  `ENDPOINT_PROPOSED_BUT_REJECTED`, `ENDPOINT_UNRESOLVED`.
- `evaluation_record` persists per-document/chunk/arm counts and rejection reasons.
- Four endpoint tests pass; existing contract tests remain green.
