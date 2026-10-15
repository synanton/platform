# ADR-014: RAG API (§51–52) Authority — 1.30-Owned, Exposed via 1.32

**Status:** Accepted 2026-10-15
**Basis:** 1.32 §§51–52 frame RAG as a higher-level *application* API over
Search; RAG is AI-execution-centric, not an external product contract.

## Decision

1.30-owned surface exposed via 1.32. Consequences:

- **BBAG-A:** deferral line stays trivial — out of scope by authority, not
  by choice. BBAG-A's OpenAPI carries no §51–52 endpoints.
- **Freeze:** §§51–52 are NOT part of the 1.32-owned frozen surface. 1.32
  normalizes exposure (errors per RFC 9457, auth, tenant scoping); 1.30
  breakage is absorbed at the 1.32 boundary.
- **Future generations:** when RAG is exposed, it follows the normalization
  rule above. Authority statement, not implementation.

## Context

A5 (BBAG-A review): leaving RAG authority undecided risked silent boundary
shaping of 1.32. Resolved before M0-contract-freeze per the review brief.
