---
title: "Synanton Documentation"
status: "current"
last_reviewed: "2026-08-26"
---

# Synanton Documentation

**Purpose:** Top-level navigation for all Synanton platform documentation. Start here to find the right document for your role.
**Current design version:** [`1.22`](./VERSION) - [`architecture/synanton-design-1.22.md`](./architecture/synanton-design-1.22.md)

## Quick Links

| Document | Description |
|----------|-------------|
| [`architecture/synanton-design-1.23.md`](./architecture/synanton-design-1.23.md) | **In progress** - classification-aware semantic search (v1.23) |
| [`architecture/synanton-design-1.22.md`](./architecture/synanton-design-1.22.md) | **Current baseline** - semantic chunking + pointers to extraction (1.21), GPU (1.20), baseline (1.19) |
| [`demos/`](./demos/) | End-to-end demo scenarios |
| [`demo/flat-vs-semantic-chunks-research-plan.md`](./demo/flat-vs-semantic-chunks-research-plan.md) | Flat vs semantic chunking RAG benchmark plan |
| [`architecture/synanton-design-1.21.md`](./architecture/synanton-design-1.21.md) | Structured Content Extraction Plane (Part IX) |
| [`architecture/synanton-design-1.20.md`](./architecture/synanton-design-1.20.md) | GPU Execution Plane (Part VIII) |
| [`implementation/synanton-phases-plan.md`](./implementation/synanton-phases-plan.md) | Master implementation phases plan |
| [`proposals/v1.22/`](./proposals/v1.22/) | Semantic chunking proposal (folded into 1.22) |
| [`proposals/v1.21/`](./proposals/v1.21/) | Extraction-plane proposal (folded into 1.21) |
| [`proposals/v1.20/`](./proposals/v1.20/) | GPU isolation proposal (folded into 1.20) |
| [`operations/`](./operations/) | Runbooks, DR playbooks, capacity planning |
| [`api/`](./api/) | REST, gRPC, and CLI reference |
| [`user-guides/`](./user-guides/) | Getting started, tenant admin, developer guides |

## Directory Overview

| Directory | Purpose | Audience | Stability |
|-----------|---------|---------|-----------|
| [`architecture/`](./architecture/) | Long-lived design decisions, ADRs, current design | Architects, module owners | **Stable** |
| [`implementation/`](./implementation/) | Phase plans, module execution guides, demos | Engineers, leads | **Volatile** |
| [`operations/`](./operations/) | Runbooks, DR, capacity, alerts | SREs, on-call | **Semi-stable** |
| [`demos/`](./demos/) | End-to-end demo scenarios with fixtures and acceptance checks | Engineers, reviewers | **Semi-stable** |
| [`demo/`](./demo/) | Research plans and benchmark designs (quality, cost) | Search / ML engineers | **Volatile** |
| [`proposals/`](./proposals/) | Versioned change proposals + approval records | Architects, approvers | **Ephemeral** |
| [`api/`](./api/) | REST/OpenAPI, gRPC/SPI, CLI reference | API consumers, connector authors | **Stable** |
| [`user-guides/`](./user-guides/) | Task-oriented guides | Tenants, admins, developers | **Stable** |
| [`contrib/`](./contrib/) | Contribution guidelines, dev setup, testing discipline | Contributors | **Semi-stable** |
| [`archive/`](./archive/) | Historical documents (read-only) | Forensics | **Frozen** |

## How "Current" Is Managed

The `VERSION` file at the root of `docs/` contains the current design version number (e.g., `1.22`).
The `architecture/INDEX.md` explicitly marks which design file is authoritative.
No symlinks are used - all files are real paths, compatible with Git on all platforms.
