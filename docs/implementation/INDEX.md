---
title: "Implementation"
status: "current"
last_reviewed: "2026-08-26"
---

# Implementation

**Purpose:** Tactical, time-bound execution plans for each platform phase, module-level deep-dives, and demo guides.
**Audience:** Engineers, leads
**Last Updated:** 2026-08-26

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-phases-plan.md`](./synanton-phases-plan.md) | **Master phases plan** - roadmap overview and phase matrix |
| [`content-extraction-plane/INDEX.md`](./content-extraction-plane/INDEX.md) | Structured Content Extraction Plane (v1.21) |
| [`semantic-chunking/INDEX.md`](./semantic-chunking/INDEX.md) | Semantic Content Structuring / Chunking (v1.22) |
| [`classification-aware-search/INDEX.md`](./classification-aware-search/INDEX.md) | Classification-Aware Semantic Search (v1.23) |
| [`annotations-analytics-plane/INDEX.md`](./annotations-analytics-plane/INDEX.md) | Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane (v1.24/1.25) |
| [`gpu-execution-plane/INDEX.md`](./gpu-execution-plane/INDEX.md) | GPU Execution Plane (v1.20) |
| [`modules/helper.md`](./modules/helper.md) | `helper` module implementation plan |
| [`modules/wizard.md`](./modules/wizard.md) | `wizard` module implementation plan |
| [`demo/standalone-syntology-demo.md`](./demo/standalone-syntology-demo.md) | Standalone Syntology demo guide |
| [`../demos/INDEX.md`](../demos/INDEX.md) | End-to-end demo scenarios (v1.23 classification demo, etc.) |
| [`../demo/INDEX.md`](../demo/INDEX.md) | Research plans and benchmark designs |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `phase1/` | Phase 1 - Foundation |
| `phase2/` | Phase 2 - LLM Online |
| `phase3/` | Phase 3 - Multi-tenant, Auth, Router |
| `phase4/` | Phase 4 - Production Hardening |
| `phase5/` | Phase 5 - Scale + Vision + DR |
| `content-extraction-plane/` | v1.21 extraction plane implementation plan |
| `semantic-chunking/` | v1.22 semantic chunking implementation plan |
| `classification-aware-search/` | v1.23 classification-aware search implementation plan |
| `annotations-analytics-plane/` | v1.24/1.25 annotation, recalculation, analytics & reporting plane implementation plan |
| `gpu-execution-plane/` | v1.20 GPU plane implementation plan |
| `modules/` | Module-specific deep-dive implementation plans |
| `demo/` | Demo and standalone guides |

## How to Contribute

Phase plans are versioned alongside the design. When a phase begins, create or update the relevant `phaseN/` directory. When a phase is complete, move its plans to `../../archive/implementation/`.
