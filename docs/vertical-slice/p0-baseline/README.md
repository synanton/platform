# P0 Baseline — Vertical Slice

Status: **P0-1 draft**. P0-2 (contract baseline), P0-3 (test snapshot), P0-4 (gap list), and P0-5 (operational verification) are not yet complete. No implementation PR may open before all required artifacts are merged.

## Artifacts

| Artifact | File | Status |
|---|---|---|
| P0-1 Ingestion inventory | `01-ingestion.md` | draft |
| P0-1 Ontology inventory | `02-ontology.md` | draft |
| P0-1 Extraction inventory | `03-extraction.md` | draft |
| P0-1 Graph inventory | `04-graph.md` | draft |
| P0-1 UI inventory | `05-ui.md` | draft |
| P0-1 Search inventory | `06-search.md` | draft |
| P0-2 Contract baseline | pending | not started |
| P0-3 Baseline test snapshot | pending | not started |
| P0-4 Gap list | pending | not started |
| P0-5 Operational verification | pending | not started |

## Evidence rules

- Evidence is **source and test-source inspection**, not a fresh test run. Test names identify coverage; P0-3 will record executed pass/fail/flaky/skipped state.
- “verified-in-code” means the behavior is present in production code. A test may be absent.
- “documented-only” means docs/config exist but the effective capability was not found.
- “unknown” means evidence was insufficient. Unknown is not treated as absent or present.
- No F1, gold, gate, profile, or ADR-044 status claim is made.
