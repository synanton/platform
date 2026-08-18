---
title: "Architecture"
status: "current"
last_reviewed: "2026-07-21"
---

# Architecture

**Purpose:** Long-lived design decisions, Architecture Decision Records (ADRs), and the current authoritative design document for the Synanton platform.
**Audience:** Architects, module owners, security engineers
**Last Updated:** 2026-07-21

> **Current approved design:** [`synanton-design-1.19.md`](./synanton-design-1.19.md)
> Previous versions are available in [`../archive/architecture/`](../archive/architecture/).

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-design-1.19.md`](./synanton-design-1.19.md) | **Current** - v1.19 merged reference (helper + wizard modules) |
| [`syntology/ontology-management.md`](./syntology/ontology-management.md) | Consolidated Syntology ontology management design |
| [`decisions/`](./decisions/) | Architecture Decision Records (ADRs) |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `syntology/` | Sub-domain design: Ontology Management |
| `decisions/` | ADRs for significant architectural choices |

## Design Version History

| Version | File | Status |
|---------|------|--------|
| 1.19 | [`synanton-design-1.19.md`](./synanton-design-1.19.md) | **CURRENT** |
| 1.18 | [`../archive/architecture/synanton-design-1.18.md`](../archive/architecture/synanton-design-1.18.md) | Superseded |
| 1.17 | [`../archive/architecture/synanton-design-1.17.md`](../archive/architecture/synanton-design-1.17.md) | Superseded |
| ≤1.16 | [`../archive/architecture/`](../archive/architecture/) | Archived |

## How to Contribute

To propose a design change: create a new proposal in `../proposals/vX.Y/`, get approval, then update this document by placing the new design file here and moving the old one to `../archive/architecture/`. Update `VERSION` and this `INDEX.md`.
