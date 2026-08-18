---
title: "synctl wizard CLI Reference"
version: "1.19"
status: "current"
audience: "DevOps engineers, platform engineers"
last_reviewed: "2026-07-21"
---

# `synctl wizard` - CLI Reference

**Module design:** [`../../architecture/synanton-design-1.19.md §26c`](../../architecture/synanton-design-1.19.md)

## Overview

`synctl wizard` generates deployment artifacts offline. No credentials are required at generation time.

## Commands

### `synctl wizard init`

Interactive questionnaire to create a `deployment-config.yaml`.

```
synctl wizard init [--output deploy.yaml]
```

The questionnaire covers:
- Deployment profile (Full / Standalone / Embedded)
- Cloud provider (AWS / GCP / Azure / Self-hosted)
- Region(s)
- Capacity tier (S / M / L - see Appendix A of design doc)

---

### `synctl wizard validate`

Validate a config file against the schema without generating files.

```
synctl wizard validate --config deploy.yaml
```

---

### `synctl wizard generate`

Generate the full artifact set from a config file.

```
synctl wizard generate --config deploy.yaml [--output-dir ./deployment]
```

**Generated artifacts:**
- `docker-compose.yml` (always)
- `terraform/` (if cloud provider is set)
- `k8s/` (Helm charts + Kustomize overlays, if Kubernetes target)
- `.env` (placeholder secrets)
- `config/application-*.yml` (per-module configuration)

---

### `synctl wizard apply`

(Optional) Run `terraform init && terraform apply` in the generated directory.

```
synctl wizard apply --config deploy.yaml [--output-dir ./deployment] [--auto-approve]
```

> Requires cloud provider credentials in the shell environment. `wizard` does not store credentials.

## Configuration

```
~/.synanton/wizard-defaults.yaml   # preferred cloud/region/capacity defaults
~/.synanton/templates/             # custom template overrides
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 4 | Schema validation failure |
| 5 | Template rendering error |
