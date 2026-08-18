---
title: "wizard - Deployment Setup Builder"
version: "1.19"
status: "current"
audience: "engineers, DevOps, platform engineers"
last_reviewed: "2026-07-21"
---

# `wizard` Module - Implementation Plan

**Design reference:** [`../../architecture/synanton-design-1.19.md §26c`](../../architecture/synanton-design-1.19.md)
**Proposal:** [`../../proposals/v1.19/Synanton Platform Version 1.19 Proposal.md`](../../proposals/v1.19/Synanton Platform Version 1.19 Proposal.md)

## Overview

The `wizard` module is an offline deployment-artifact generator (`synctl wizard`) that produces Terraform, Kubernetes Helm charts, Docker Compose files, and `.env` files from a declarative `deployment-config.yaml`. It requires zero live-cluster credentials at generation time.

## Phase Delivery

| Phase | Deliverables |
|-------|-------------|
| **Phase 1** | `init`, `generate` (Docker Compose, single-node Full profile), `validate`; `.env` and `application-*.yml` output |
| **Phase 2** | Terraform for AWS/GCP/Azure; Kubernetes Helm chart generation; `apply` command |
| **Phase 3** | No changes |
| **Phase 4** | Update reverse-proxy templates for v1.18 CSP/security headers |
| **Phase 5** | Multi-region DR generation; Glacier lifecycle rules; `BackupVerificationWorkflow` CronJob manifests |

## Phase 1 Tasks

- [ ] Define `wizard/schema/v1.json` (deployment config schema)
- [ ] Implement `synctl wizard init` (interactive questionnaire → `deployment-config.yaml`)
- [ ] Implement `synctl wizard validate --config deploy.yaml`
- [ ] Implement `synctl wizard generate --config deploy.yaml` for Docker Compose output
- [ ] Create built-in templates (`tools/synanton-ops/internal/templates/docker-compose/`)
- [ ] Generate `.env` with placeholder secrets
- [ ] Generate `application-*.yml` files per module (CPU/memory from Appendix A of design doc)
- [ ] Write unit tests for each template type
- [ ] Write integration test: `init` → `validate` → `generate` round-trip

## Phase 2 Tasks

- [ ] Add Terraform template sets for AWS, GCP, Azure (`templates/terraform/{aws,gcp,azure}/`)
- [ ] Implement Kubernetes Helm chart templates
- [ ] Implement Kustomize overlays for dev/staging/prod
- [ ] Implement `synctl wizard apply --config deploy.yaml` (shells out to `terraform`)
- [ ] Document `apply` credential requirements

## Phase 4 Tasks

- [ ] Update Nginx/Gateway reverse-proxy templates with CSP headers from v1.18 §49
- [ ] Validate generated configs against v1.18 security header requirements

## Phase 5 Tasks

- [ ] Add multi-region DR Terraform templates (CRR, MirrorMaker 2, multi-DC Cassandra)
- [ ] Add S3 Glacier lifecycle rule generation
- [ ] Add `BackupVerificationWorkflow` CronJob Kubernetes manifest template

## Security Notes

- No credentials are stored by `wizard` at any point.
- Generated `.env` files use clearly labelled placeholders (`_PLACEHOLDER` suffix).
- `apply` reads cloud credentials from the shell environment - never from wizard config.
- Generated Terraform uses `random_password` resources where possible to defer secret generation to Terraform itself.
