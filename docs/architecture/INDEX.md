---
title: "Architecture"
status: "current"
last_reviewed: "2026-08-26"
---

# Architecture

**Purpose:** Long-lived design decisions, Architecture Decision Records (ADRs), and the current authoritative design document for the Synanton platform.
**Audience:** Architects, module owners, security engineers
**Last Updated:** 2026-08-26

> **Current approved design:** [`synanton-design-1.21.md`](./synanton-design-1.21.md)
> Previous versions are available in [`../archive/architecture/`](../archive/architecture/).

### Reading the merged-reference chain

v1.20 and v1.21 are **incremental merged references**: each restates its predecessor as
authoritative for unchanged areas rather than reproducing it. To read the platform design
in full, start at v1.21 and follow it back:

| For | Read |
|---|---|
| Document processing — Part IX (§65–§79) | [`synanton-design-1.21.md`](./synanton-design-1.21.md) |
| GPU Execution Plane — Part VIII (§50–§64) | [`synanton-design-1.20.md`](./synanton-design-1.20.md) |
| v1.20 deltas to §1, §3, §4, §5, §23, §26, §45, §47, §48 | [`synanton-design-1.20.md`](./synanton-design-1.20.md) |
| Complete baseline §1–§49 | [`synanton-design-1.19.md`](./synanton-design-1.19.md) |

Note that v1.20 reproduces only the nine Parts I–VII sections it modifies, not all of §1–§49;
v1.19 remains the complete baseline. v1.20 and v1.19 therefore remain in this directory rather
than being archived: they are still the authoritative text for what v1.21 does not restate.
They are **superseded as the entry point**, not as content.

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-design-1.21.md`](./synanton-design-1.21.md) | **Current** - v1.21 merged reference (structured document processing) |
| [`synanton-design-1.20.md`](./synanton-design-1.20.md) | Authoritative for Part VIII, GPU Execution Plane (§50–§64) |
| [`synanton-design-1.19.md`](./synanton-design-1.19.md) | Authoritative for §1–§49 (baseline platform) |
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
| 1.21 | [`synanton-design-1.21.md`](./synanton-design-1.21.md) | **CURRENT** - entry point |
| 1.20 | [`synanton-design-1.20.md`](./synanton-design-1.20.md) | Superseded as entry point; authoritative for §50–§64 |
| 1.19 | [`synanton-design-1.19.md`](./synanton-design-1.19.md) | Superseded as entry point; authoritative for §1–§49 |
| 1.18 | [`../archive/architecture/synanton-design-1.18.md`](../archive/architecture/synanton-design-1.18.md) | Superseded |
| 1.17 | [`../archive/architecture/synanton-design-1.17.md`](../archive/architecture/synanton-design-1.17.md) | Superseded |
| ≤1.16 | [`../archive/architecture/`](../archive/architecture/) | Archived |

## How to Contribute

To propose a design change: create a new proposal in `../proposals/vX.Y/`, get approval, then
update this document by placing the new design file here. Update `../VERSION` and this `INDEX.md`.

**On archiving:** a superseded design moves to `../archive/architecture/` only once its content
is fully restated by a later version. An incremental merged reference (v1.20, v1.21) leaves its
predecessor in place, because that predecessor is still the authoritative text for the sections
the newer document declares unchanged. Archiving it would break the chain described above.
