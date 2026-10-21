---
title: "Synanton Documentation"
status: "current"
last_reviewed: "2026-10-21"
---

# Synanton Documentation

**Purpose:** Top-level navigation for platform contracts, implementation evidence, operations, and historical design records.
**Architecture overview:** [Platform Architecture 1.0](./architecture/synanton-platform-architecture-1.0.md), integrating approved Designs 1.22–1.34. Plane designs remain authoritative within their own domains.

The project uses these status labels: **Proposed**, **Approved (design)**, **Contract frozen**, **In progress**, **Implemented**, **Validated (with evidence/date)**, and **Superseded**. Approval is an architecture decision, not an implementation or production-readiness claim.

## Quick Links

| Document | Description |
|----------|-------------|
| [`architecture/INDEX.md`](./architecture/INDEX.md) | Canonical design hierarchy, ADRs, and per-plane status |
| [`architecture/synanton-platform-architecture-1.0.md`](./architecture/synanton-platform-architecture-1.0.md) | Approved architecture capstone; §15 records design vs implementation status |
| [`acceptance/`](./acceptance/) | Dated acceptance records and evidence (including superseded run snapshots) |
| [`demo/README.md`](./demo/README.md) | Current Phase 1 BM25 demo scope and limitations |
| [`demo/demo-script.md`](./demo/demo-script.md) | UI upload → BM25 search → provenance walkthrough |
| [`security/security-model.md`](./security/security-model.md) | Shared security contract and dated enforcement scope |
| [`implementation/`](./implementation/) | Implementation plans and evidence; check each plan's current status header |
| [`api/INDEX.md`](./api/INDEX.md) | REST, gRPC, and CLI reference |
| [`operations/INDEX.md`](./operations/INDEX.md) | Operations guides and runbooks |
| [`research/INDEX.md`](./research/INDEX.md) | Research plans and benchmark records |
| [`demos/INDEX.md`](./demos/INDEX.md) | Historical and component-level demo scenarios |
| [`DOCUMENTATION-INVENTORY.md`](./DOCUMENTATION-INVENTORY.md) | Disposition of the platform's current-state documentation set |

## Directory Overview

| Directory | Purpose | Audience | Stability |
|-----------|---------|---------|-----------|
| [`architecture/`](./architecture/) | Approved designs, ADRs, and superseded history | Architects, module owners | **Stable; status changes dated** |
| [`implementation/`](./implementation/) | Plans and execution evidence | Engineers, leads | **Volatile; plans own their status** |
| [`operations/`](./operations/) | Runbooks, DR, capacity, alerts | SREs, on-call | **Semi-stable** |
| [`demos/`](./demos/) | End-to-end demo scenarios with fixtures and acceptance checks | Engineers, reviewers | **Semi-stable** |
| [`demo/`](./demo/) | Research plans and benchmark designs (quality, cost) | Search / ML engineers | **Volatile** |
| [`architecture/proposals/`](./architecture/proposals/) | Proposals and review resolutions; accepted decisions link to canonical design/ADR | Architects, approvers | **Historical after disposition** |
| [`api/`](./api/) | REST/OpenAPI, gRPC/SPI, CLI reference | API consumers, connector authors | **Stable** |
| [`user-guides/`](./user-guides/) | Task-oriented guides | Tenants, admins, developers | **Stable** |
| [`contrib/`](./contrib/) | Contribution guidelines, dev setup, testing discipline | Contributors | **Semi-stable** |
| [`architecture/archive/`](./architecture/archive/) | Superseded design history (read-only) | Forensics, implementers | **Frozen** |

## Sources of truth

- Architecture approval and design hierarchy: [`architecture/INDEX.md`](./architecture/INDEX.md) and the linked ADRs.
- Current platform demo acceptance: the dated record in [`acceptance/`](./acceptance/); older records remain snapshots and carry later corrections where applicable.
- Component implementation: the owning repository's code/tests and linked implementation plan. A design approval does not imply implementation.
- Cross-plane implementation status: [Platform README](../README.md) and capstone §15, with dated updates.

`VERSION` identifies the baseline design series, not the latest architecture capstone or implementation level. Historical documents are retained when they explain decisions; superseded items identify their canonical replacement.
