---
title: "Synanton Documentation"
status: "current"
last_reviewed: "2026-07-21"
---

# Synanton Documentation

**Purpose:** Top-level navigation for all Synanton platform documentation. Start here to find the right document for your role.
**Current design version:** [`1.21`](./VERSION) - [`architecture/synanton-design-1.21.md`](./architecture/synanton-design-1.21.md)

## Quick Links

| Document | Description |
|----------|-------------|
| [`architecture/synanton-design-1.21.md`](./architecture/synanton-design-1.21.md) | **Current authoritative design** - entry point to the merged-reference chain |
| [`architecture/INDEX.md`](./architecture/INDEX.md) | How to read the v1.21 → v1.20 → v1.19 chain |
| [`implementation/synanton-phases-plan.md`](./implementation/synanton-phases-plan.md) | Master implementation phases plan |
| [`proposals/v1.21/`](./proposals/v1.21/) | Latest accepted proposal (structured PDF parsing) |
| [`operations/`](./operations/) | Runbooks, DR playbooks, capacity planning |
| [`api/`](./api/) | REST, gRPC, and CLI reference |
| [`user-guides/`](./user-guides/) | Getting started, tenant admin, developer guides |

## Directory Overview

| Directory | Purpose | Audience | Stability |
|-----------|---------|---------|-----------|
| [`architecture/`](./architecture/) | Long-lived design decisions, ADRs, current design | Architects, module owners | **Stable** |
| [`implementation/`](./implementation/) | Phase plans, module execution guides, demos | Engineers, leads | **Volatile** |
| [`operations/`](./operations/) | Runbooks, DR, capacity, alerts | SREs, on-call | **Semi-stable** |
| [`proposals/`](./proposals/) | Versioned change proposals + approval records | Architects, approvers | **Ephemeral** |
| [`api/`](./api/) | REST/OpenAPI, gRPC/SPI, CLI reference | API consumers, connector authors | **Stable** |
| [`user-guides/`](./user-guides/) | Task-oriented guides | Tenants, admins, developers | **Stable** |
| [`contrib/`](./contrib/) | Contribution guidelines, dev setup, testing discipline | Contributors | **Semi-stable** |
| [`archive/`](./archive/) | Historical documents (read-only) | Forensics | **Frozen** |

## How "Current" Is Managed

The `VERSION` file at the root of `docs/` contains the current design version number (e.g., `1.21`).
The `architecture/INDEX.md` explicitly marks which design file is authoritative.
No symlinks are used - all files are real paths, compatible with Git on all platforms.

Since v1.20 the design is a **chain of incremental merged references**: the current document
restates its predecessor as authoritative for unchanged areas instead of reproducing it. The
version in `VERSION` names the **entry point**, not the only authoritative file. See
[`architecture/INDEX.md`](./architecture/INDEX.md) for which document owns which sections.
