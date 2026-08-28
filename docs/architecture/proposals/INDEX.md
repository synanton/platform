---
title: "Proposals"
status: "current"
last_reviewed: "2026-08-26"
---

# Proposals

**Purpose:** Versioned change proposals and their approval records. Proposals are pre-design documents - they become part of the authoritative design once accepted and folded into the main design document.
**Audience:** Architects, approvers
**Last Updated:** 2026-08-26

## Quick Links

| Proposal             | Status                                                                  |
|----------------------|-------------------------------------------------------------------------|
| [`synanton-design-1.23.md`](../synanton-design-1.23.md) | Folded into architecture 1.23 - Classification-Aware Semantic Search |
| [`../implementation/classification-aware-search/`](../implementation/classification-aware-search/) | v1.23 implementation plan |
| [`../demos/classification-aware-semantic-search-demo.md`](../../demos/classification-aware-semantic-search-demo.md) | v1.23 demo scenario |
| [`v1.22/`](v1.22/) | Folded into architecture 1.22 - Semantic Content Structuring / Chunking |
| [`v1.21/`](v1.21/) | Folded into architecture 1.21 - Structured Content Extraction Plane     |
| [`v1.20/`](v1.20/) | Folded into architecture 1.20 - GPU Execution Plane isolation           |
| [`v1.19/`](./v1.19/) | Folded into architecture 1.19 - Helper & Wizard modules                 |
| [`v1.18/`](./v1.18/) | Accepted - Data validation & XSS protection                             |
| [`v1.17/`](./v1.17/) | Accepted - Operational robustness & DR                                  |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `v1.17/` | v1.17 proposal + decision record |
| `v1.18/` | v1.18 proposal + decision record |
| `v1.20/` | GPU isolation proposal |
| `v1.21/` | Extraction plane proposal |
| `v1.22/` | Semantic chunking proposal |
| `v1.19/` | v1.19 proposal + decision record |
| `templates/` | Proposal document template |

## Lifecycle

1. Author creates `vX.Y/` with the proposal document.
2. Review and approval adds `vX.Y/decision.md` (Approved / Rejected / Superseded).
3. On approval, content is folded into `../architecture/synanton-design-X.Y.md`.
4. Old proposals remain here for historical reference.
