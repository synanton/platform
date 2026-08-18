---
title: "Implementation"
status: "current"
last_reviewed: "2026-07-21"
---

# Implementation

**Purpose:** Tactical, time-bound execution plans for each platform phase, module-level deep-dives, and demo guides.
**Audience:** Engineers, leads
**Last Updated:** 2026-07-21

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-phases-plan.md`](./synanton-phases-plan.md) | **Master phases plan** - roadmap overview and phase matrix |
| [`modules/helper.md`](./modules/helper.md) | `helper` module implementation plan |
| [`modules/wizard.md`](./modules/wizard.md) | `wizard` module implementation plan |
| [`demo/standalone-syntology-demo.md`](./demo/standalone-syntology-demo.md) | Standalone Syntology demo guide |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `phase1/` | Phase 1 - Foundation |
| `phase2/` | Phase 2 - LLM Online |
| `phase3/` | Phase 3 - Multi-tenant, Auth, Router |
| `phase4/` | Phase 4 - Production Hardening |
| `phase5/` | Phase 5 - Scale + Vision + DR |
| `modules/` | Module-specific deep-dive implementation plans |
| `demo/` | Demo and standalone guides |

## How to Contribute

Phase plans are versioned alongside the design. When a phase begins, create or update the relevant `phaseN/` directory. When a phase is complete, move its plans to `../../archive/implementation/`.
