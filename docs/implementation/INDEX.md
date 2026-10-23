---
title: "Implementation"
status: "current"
last_reviewed: "2026-10-23"
---

# Implementation

**Purpose:** Current plane-specific execution plans, module-level references, and demo guides. The July 2026 module-phase folders are planning history, not the current delivery-milestone status source.
**Audience:** Engineers, leads
**Last Updated:** 2026-10-23

## Quick Links

| Document | Description |
|----------|-------------|
| [Current accepted Phase 1 demo](../../README.md#quick-start) | BM25 UI upload → search status, constraints, and dated acceptance evidence |
| [P0 acceptance record](../acceptance/p0-ingestion-search-2026-10-21.md) | Detailed run evidence; the five-check result and vector scope are explicit |
| [`synanton-phases-plan.md`](./synanton-phases-plan.md) | **Historical (July 2026)** - module-phase roadmap; its Phase 1/2 labels are not current delivery milestones |
| [`content-extraction-plane/INDEX.md`](./content-extraction-plane/INDEX.md) | Structured Content Extraction Plane (v1.21) |
| [`semantic-chunking/INDEX.md`](./semantic-chunking/INDEX.md) | Semantic Content Structuring / Chunking (v1.22) |
| [`classification-aware-search/INDEX.md`](./classification-aware-search/INDEX.md) | Classification-Aware Semantic Search (v1.23) |
| [`annotations-analytics-plane/INDEX.md`](./annotations-analytics-plane/INDEX.md) | Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane (v1.24/1.25) |
| [`gpu-execution-plane/INDEX.md`](./gpu-execution-plane/INDEX.md) | GPU Execution Plane (v1.20) |
| [`eventing-workflow-plane/INDEX.md`](./eventing-workflow-plane/INDEX.md) | Eventing and Workflow Plane (v1.27) - EWP-1 baseline `7523fae`, type/version admission follow-up #396, EWP-2 publication #394 |
| [`platform-api-plane/INDEX.md`](./platform-api-plane/INDEX.md) | Platform API Plane (v1.32) - contracts frozen 2026-10-17 (#246); generation-A in progress |
| [`folder-upload-328.md`](./folder-upload-328.md) | Tenant-scoped document folder uploads through the shared ingestion contract (#328) |
| [`library-deployment-topology.md`](./library-deployment-topology.md) | Personal-library placement: UI in k8s, crawler on M70q, web upload with the platform |
| [`benchmark-runner-vector/INDEX.md`](./benchmark-runner-vector/INDEX.md) | Benchmark Runner + Vector Engine Selection — 48-task plan (BR 12 + VEC 24 + DOC 12) |
| [`benchmark-tracker.md`](./benchmark-tracker.md) | Benchmark execution tracker (targets, gates, aliases) |
| [`modules/helper.md`](./modules/helper.md) | `helper` module implementation plan |
| [`modules/wizard.md`](./modules/wizard.md) | `wizard` module implementation plan |
| [`demo/standalone-syntology-demo.md`](./demo/standalone-syntology-demo.md) | Standalone Syntology demo guide |
| [`ydb-poc-implementation-plan.md`](./ydb-poc-implementation-plan.md) | YDB PoC implementation plan + ticket tracker (merged PoC record; throwaway-scoped, 010 resolved) |
| [`ydb-poc/README.md`](./ydb-poc/README.md) | YDB PoC artifact index — ports, contracts, specs, decision records |
| [`../demos/INDEX.md`](../demos/INDEX.md) | End-to-end demo scenarios (v1.23 classification demo, etc.) |
| [`../research/INDEX.md`](../research/INDEX.md) | Research plans and benchmark designs |

## Sub-directories

The `phase1/` and `phase2/` plans preserve the earlier module-by-module
sequencing. Their phase numbers are not interchangeable with the accepted
user-facing Phase 1 BM25 demo in the README. `phase3/`–`phase5/` are directional
module plans; use each plan's own status and the architecture implementation
table rather than inferring delivery acceptance from a directory name.

| Directory | Purpose |
|-----------|---------|
| `phase1/` | Historical module plan - Foundation |
| `phase2/` | Module plan - LLM enrichment and online capabilities (no current delivery acceptance implied) |
| `phase3/` | Phase 3 - Multi-tenant, Auth, Router |
| `phase4/` | Phase 4 - Production Hardening |
| `phase5/` | Phase 5 - Scale + Vision + DR |
| `content-extraction-plane/` | v1.21 extraction plane implementation plan |
| `semantic-chunking/` | v1.22 semantic chunking implementation plan |
| `classification-aware-search/` | v1.23 classification-aware search implementation plan |
| `annotations-analytics-plane/` | v1.24/1.25 annotation, recalculation, analytics & reporting plane implementation plan |
| `gpu-execution-plane/` | v1.20 GPU plane implementation plan |
| `eventing-workflow-plane/` | v1.27 contract frozen 2026-10-17 (#247); EWP-1 baseline `7523fae` with type/version follow-up #396; EWP-2 tracked in #394 |
| `platform-api-plane/` | v1.32 contracts frozen 2026-10-17 (#246); generation-A implementation in progress |
| `modules/` | Module-specific deep-dive implementation plans |
| `demo/` | Demo and standalone guides |
| `ydb-poc/` | YDB PoC decision records, specs, and test artifacts (merged PoC record) |

## How to Contribute

Phase plans are versioned alongside the design. When a phase begins, create or update the relevant `phaseN/` directory. When a phase is complete, move its plans to `../../archive/implementation/`.
