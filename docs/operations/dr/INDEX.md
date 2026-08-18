---
title: "Disaster Recovery"
status: "current"
last_reviewed: "2026-07-21"
---

# Disaster Recovery

**Purpose:** Disaster recovery playbooks for regional failover and failback scenarios.
**Audience:** SREs, on-call engineers
**Last Updated:** 2026-07-21

> DR architecture is defined in §47a of [`../../architecture/synanton-design-1.19.md`](../../architecture/synanton-design-1.19.md).

## Playbooks

| Playbook | Description |
|---------|-------------|
| [`DR1-regional-failover.md`](./DR1-regional-failover.md) | Promote DR region to primary |
| [`DR2-failback.md`](./DR2-failback.md) | Failback to original primary after incident resolution |

## RTO/RPO Targets (from §47a)

| Storage | RPO | RTO |
|---------|-----|-----|
| PostgreSQL (topology, audit) | ≤ 1 min | ≤ 5 min |
| Cassandra (ingestion-cache) | ≤ 5 min | ≤ 15 min |
| Kafka | ≤ 30 s (MirrorMaker 2 lag) | ≤ 10 min |
| S3 / object storage | ≤ 15 min (CRR lag) | ≤ 30 min |
