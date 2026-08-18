---
title: "Runbooks"
status: "current"
last_reviewed: "2026-07-21"
---

# Runbooks

**Purpose:** Step-by-step incident response procedures for each platform alert.
**Audience:** SREs, on-call engineers
**Last Updated:** 2026-07-21

> Runbooks are referenced from alerts defined in §45 of [`../../architecture/synanton-design-1.19.md`](../../architecture/synanton-design-1.19.md).

## Runbook Index

| Runbook | Alert | Description |
|---------|-------|-------------|
| R1 | `RetentionThreatened` | Storage retention nearing policy limit |
| R2 | `AclStuckGrant` | ACL grant stuck in PENDING_PROPAGATION |
| R3 | `OutboundTokenSlaBreached` | Outbound token exchange p99 > 100 ms |
| R4 | `GatewayDegradedMode` | Gateway operating in emulated/degraded mode |
| R5 | `BudgetExhausted` | Tenant GPU/token budget exhausted |
| R6 | `SynfluxDegraded` | Ingestion engine in degraded mode |
| R7 | `RecrawlStalled` | Recrawl workflow not progressing |
| R8 | `ApiKeyPastExpiry` | API key within or past expiry window |
| R9 | `HelperDestructiveOpsRate` | Unexpected burst of helper delete operations |
| R10 | `HelperAuthFailureSpike` | Helper auth failure spike - possible credential issue |

## How to Contribute

Create runbooks as `R{N}-{alert-slug}.md`. Each runbook should contain:
1. Alert context (when it fires, severity)
2. Immediate triage steps
3. Root cause identification
4. Resolution steps
5. Escalation path
6. Post-incident checklist
