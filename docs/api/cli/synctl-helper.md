---
title: "synctl helper CLI Reference"
version: "1.19"
status: "current"
audience: "SREs, support engineers"
last_reviewed: "2026-07-21"
---

# `synctl helper` - CLI Reference

**Module design:** [`../../architecture/synanton-design-1.19.md §26b`](../../architecture/synanton-design-1.19.md)

## Authentication

`synctl helper` requires a `support_admin` service principal API key.

```sh
export SYNANTON_API_ENDPOINT=https://synanton.internal
export SYNANTON_SUPPORT_KEY=syn_acme_2607_<secret>
# or: use ~/.synanton/credentials
```

## Commands

### `synctl helper status`

Display aggregated cluster health.

```
synctl helper status [--output json|table]
```

**Example output:**
```
Cluster Health: HEALTHY
Modules:
  synflux   UP   v1.19.0
  synquest  UP   v1.19.0
  relix     UP   v1.19.0
Storage:
  cassandra UP
  postgres  UP
  kafka     UP
  redis     UP
Degraded mode: false
```

---

### `synctl helper bundle`

Generate a support bundle and return a pre-signed download URL.

```
synctl helper bundle [--include-logs-hours N] [--no-anonymize]
```

Sensitive fields (JWT secrets, DB passwords) are automatically stripped.

---

### `synctl helper clean`

Purge caches or orphan chunks.

```
synctl helper clean orphans [--no-dry-run]
synctl helper clean tenant --tenant <id> --cache <embedding|synthesis|all>
```

> `--dry-run` is the default for all clean operations. Pass `--no-dry-run` to execute.

---

### `synctl helper delete`

Delete platform resources. **Destructive - use with care.**

```
synctl helper delete content --ref <uuid> [--cascade] --confirm
synctl helper delete tenant --tenant <id> --confirm
```

Both commands require an interactive `--confirm` prompt.

---

### `synctl helper recrawl`

Manage recrawl workflows.

```
synctl helper recrawl start  --tenant <id> [--priority recency_weighted|uniform]
synctl helper recrawl status --tenant <id>
synctl helper recrawl pause  --tenant <id>
```

---

### `synctl helper workflow`

Manage long-running workflows.

```
synctl helper workflow cancel --id <workflow-id>
synctl helper workflow retry  --id <workflow-id>
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Authentication failure |
| 3 | Cluster unreachable |
| 4 | Validation / input error |
