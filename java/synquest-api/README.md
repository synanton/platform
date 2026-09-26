# synquest-api

Retrieval port used by Search 1.31 (`SynquestEngine` query-facing;
`SynquestIndexWriter`/`SynquestIndexAdmin` for projection mutation/lifecycle).
Depends only on `storage-contract`; no provider or cross-API imports
(`ApiBoundaryTest`). Eligibility and temporal constraints are pre-ranking by
contract. Phase-0 PoC scope; stable port-owned types (no provisional marks).
