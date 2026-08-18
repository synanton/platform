---
title: "Synanton Documentation"
status: "current"
last_reviewed: "2026-07-21"
---

# Synanton Documentation

**Purpose:** Top-level navigation for all Synanton platform documentation. Start here to find the right document for your role.
**Current design version:** [`1.19`](./VERSION) - [`architecture/synanton-design-1.19.md`](./architecture/synanton-design-1.19.md)

## Quick Links

| Document | Description |
|----------|-------------|
| [`architecture/synanton-design-1.19.md`](./architecture/synanton-design-1.19.md) | **Current authoritative design** - single merged reference |
| [`implementation/synanton-phases-plan.md`](./implementation/synanton-phases-plan.md) | Master implementation phases plan |
| [`proposals/v1.19/`](./proposals/v1.19/) | Latest accepted proposal (helper + wizard modules) |
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

The `VERSION` file at the root of `docs/` contains the current design version number (e.g., `1.19`).
The `architecture/INDEX.md` explicitly marks which design file is authoritative.
No symlinks are used - all files are real paths, compatible with Git on all platforms.
