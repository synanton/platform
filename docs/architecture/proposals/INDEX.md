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
| [`v1.33/`](v1.33/) | Folded into architecture 1.33 - Kubernetes readiness and independent operators |
| [`v1.32/`](v1.32/) | Folded into architecture 1.32 - Stable Platform API and compatibility |
| [`v1.31/`](v1.31/) | Folded into architecture 1.31 - Lexical/vector/hybrid/graph retrieval and ranking |
| [`v1.30/`](v1.30/) | Folded into architecture 1.30 - Model execution contract |
| [`v1.29/`](v1.29/) | Folded into architecture 1.29 - Principal, federation, tenant membership, delegation |
| [`v1.28/`](v1.28/) | Folded into architecture 1.28 - Source identity, versioning, synchronization, deletion |
| [`v1.27/`](v1.27/) | Folded into architecture 1.27 - Events, commands, workflows, retries, recovery |
| [`v1.26/`](v1.26/) | Folded into architecture 1.26 - Content artifact contract |
| [`v1.24-1.25/`](v1.24-1.25/) | Folded into architecture 1.25 (`synanton-design-1.25.md`) - Annotations, Derived Knowledge, Recalculation and Analytics/Reporting Plane |
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
| `v1.33/` | v1.33 proposal - Kubernetes readiness and independent operators |
| `v1.32/` | v1.32 proposal - Stable Platform API and compatibility |
| `v1.31/` | v1.31 proposal - Lexical/vector/hybrid/graph retrieval and ranking |
| `v1.30/` | v1.30 proposal - Model execution contract |
| `v1.29/` | v1.29 proposal - Principal, federation, tenant membership, delegation |
| `v1.28/` | v1.28 proposal - Source identity, versioning, synchronization, deletion |
| `v1.27/` | v1.27 proposal - Events, commands, workflows, retries, recovery |
| `v1.26/` | v1.26 proposal - Content artifact contract |
| `v1.24-1.25/` | v1.24/25 proposal - Annotations, derived knowledge, recalculation & analytics/reporting plane proposal |
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
