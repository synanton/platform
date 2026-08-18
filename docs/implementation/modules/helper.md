---
title: "helper - Operational Day-2 CLI"
version: "1.19"
status: "current"
audience: "engineers, SREs"
last_reviewed: "2026-07-21"
---

# `helper` Module - Implementation Plan

**Design reference:** [`../../architecture/synanton-design-1.19.md §26b`](../../architecture/synanton-design-1.19.md)
**Proposal:** [`../../proposals/v1.19/Synanton Platform Version 1.19 Proposal.md`](../../proposals/v1.19/Synanton Platform Version 1.19 Proposal.md)

## Overview

The `helper` module is an operational CLI (`synctl helper`) that executes day-2 support tasks exclusively via the platform's internal admin API (`/admin/_internal/*`). It never accesses the database, Kafka, or Kubernetes directly.

## Phase Delivery

| Phase | Deliverables |
|-------|-------------|
| **Phase 1** | `status`, `bundle`, `clean orphans --dry-run`; `support_admin` role; `/admin/_internal/status` endpoint |
| **Phase 2** | `recrawl start/status/pause`; `clean tenant` |
| **Phase 3** | No changes |
| **Phase 4** | `delete content`, `delete tenant`; `workflow cancel/retry`; metrics and alerts |
| **Phase 5** | DR replication lag in `status`; multi-tenant batch recrawl; key expiry reminder |

## Phase 1 Tasks

- [ ] Add `support_admin` role to `security.roles` table (migration)
- [ ] Implement `/admin/_internal/status` in `synapt`
- [ ] Implement `security.ApiKeyService.createSupportAdmin()`
- [ ] Create Go binary skeleton (`tools/synanton-ops/`)
- [ ] Implement `synctl helper status` command
- [ ] Implement `synctl helper bundle` command
- [ ] Implement `synctl helper clean orphans --dry-run`
- [ ] Implement credential resolution (`SYNANTON_API_ENDPOINT`, `SYNANTON_SUPPORT_KEY`, `~/.synanton/credentials`)
- [ ] Implement key-expiry warning (≤ 7 days)
- [ ] Update `synctl` wrapper to dispatch `helper` subcommand to `synanton-ops`
- [ ] Write unit tests (mock HTTP client)
- [ ] Write integration tests against in-process `synapt`

## Phase 2 Tasks

- [ ] Implement `/admin/_internal/recrawl` endpoint in `control-plane`
- [ ] Wire to existing `RecrawlWorkflow` orchestrator
- [ ] Implement `synctl helper recrawl start/status/pause`
- [ ] Implement `/admin/_internal/clean` for `clean tenant`
- [ ] Implement `synctl helper clean tenant`

## Phase 4 Tasks

- [ ] Implement `/admin/_internal/delete` for content and tenant
- [ ] Implement `synctl helper delete content` and `delete tenant` with `--confirm` gate
- [ ] Implement `/admin/_internal/workflow/cancel` and `/retry`
- [ ] Implement `synctl helper workflow cancel/retry`
- [ ] Add `helper_operation_total` metric scrape config
- [ ] Add `HelperDestructiveOpsRate` and `HelperAuthFailureSpike` alerts
- [ ] Add `admin_audit` `before_state_hash` / `after_state_hash` columns (migration)

## Security Notes

- All commands require `support_admin` role - not assignable to human users.
- Destructive commands (`delete`) require interactive `--confirm` flag.
- `clean orphans` defaults to `--dry-run=true`.
- `bundle` strips JWT secrets and DB passwords from output.
- `status` never exposes internal IPs or connection strings.
