---
title: "Architecture"
status: "current"
last_reviewed: "2026-08-26"
---

# Architecture

**Purpose:** Long-lived design decisions, Architecture Decision Records (ADRs), and the current authoritative design document for the Synanton platform.
**Audience:** Architects, module owners, security engineers
**Last Updated:** 2026-08-26

> **Current approved design:** [`synanton-design-1.22.md`](./synanton-design-1.22.md) (platform baseline)
> **v1.23 (in progress):** [`synanton-design-1.23.md`](./synanton-design-1.23.md) — classification-aware semantic search
> Extraction plane (Part IX): [`synanton-design-1.21.md`](./synanton-design-1.21.md)
> GPU Execution Plane detail: [`synanton-design-1.20.md`](./synanton-design-1.20.md)
> 1.19 is the merged baseline for unchanged core sections - **not** the live pointer.

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-design-1.23.md`](./synanton-design-1.23.md) | **In progress** - classification-aware semantic search (v1.23) |
| [`synanton-design-1.22.md`](./synanton-design-1.22.md) | **Current baseline** - semantic chunking + pointers to 1.21 extraction / 1.20 GPU / 1.19 baseline |
| [`synanton-design-1.21.md`](./synanton-design-1.21.md) | Structured Content Extraction Plane (Part IX) |
| [`synanton-design-1.20.md`](./synanton-design-1.20.md) | GPU Execution Plane (Part VIII) |
| [`synanton-design-1.19.md`](./synanton-design-1.19.md) | Superseded pointer; still the merged Parts I–VII baseline |
| [`syntology/ontology-management.md`](./syntology/ontology-management.md) | Syntology ontology management |
| [`decisions/`](./decisions/) | Architecture Decision Records (ADRs) |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `syntology/` | Sub-domain design: Ontology Management |
| `decisions/` | ADRs for significant architectural choices |

## Design Version History

| Version | File | Status |
|---------|------|--------|
| 1.23 | [`synanton-design-1.23.md`](./synanton-design-1.23.md) | In progress (classification-aware search) |
| 1.22 | [`synanton-design-1.22.md`](./synanton-design-1.22.md) | **CURRENT baseline** |
| 1.21 | [`synanton-design-1.21.md`](./synanton-design-1.21.md) | Extraction plane (Part IX; folded) |
| 1.20 | [`synanton-design-1.20.md`](./synanton-design-1.20.md) | GPU plane (folded; still the Part VIII text) |
| 1.19 | [`synanton-design-1.19.md`](./synanton-design-1.19.md) | Superseded as current; baseline for core modules |
| 1.18 | [`../archive/architecture/synanton-design-1.18.md`](../archive/architecture/synanton-design-1.18.md) | Superseded |
| 1.17 | [`../archive/architecture/synanton-design-1.17.md`](../archive/architecture/synanton-design-1.17.md) | Superseded |
| ≤1.16 | [`../archive/architecture/`](../archive/architecture/) | Archived |

## How to Contribute

To propose a design change: create a new proposal in `../proposals/vX.Y/`, get approval, then update the current design file here (and this `INDEX.md` plus `../VERSION`). Move only fully replaced documents to `../archive/architecture/`.
