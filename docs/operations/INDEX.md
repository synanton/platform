---
title: "Operations"
status: "current"
last_reviewed: "2026-08-26"
---

# Operations

**Purpose:** Day-2 operational documentation - runbooks, disaster recovery playbooks, capacity planning, monitoring, and upgrade guides.
**Audience:** SREs, on-call engineers
**Last Updated:** 2026-07-21

## Quick Links

| Document | Description |
|----------|-------------|
| [`runbooks/`](./runbooks/) | Incident response runbooks (R1–R8) |
| [`dr/`](./dr/) | Disaster Recovery playbooks |
| [`capacity-planning.md`](./capacity-planning.md) | Capacity planning guide (see also Appendix A of design doc) |
| [`monitoring-alerts.md`](./monitoring-alerts.md) | Alert catalogue and escalation paths |
| [`upgrade-migration.md`](./upgrade-migration.md) | Version upgrade and migration procedures |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `runbooks/` | Per-alert incident response procedures |
| `dr/` | Disaster Recovery (regional failover, failback) |

## How to Contribute

Runbooks should be created when a new alert is added to the system. Each runbook should have a corresponding alert in §45 of the design document. Use the runbook naming convention `R{N}-{alert-slug}.md`.
