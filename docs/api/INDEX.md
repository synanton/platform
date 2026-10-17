---
title: "API Reference"
status: "current"
last_reviewed: "2026-10-17"
---

# API Reference

**Purpose:** REST/OpenAPI, gRPC/SPI, and CLI reference documentation for Synanton platform APIs.
**Audience:** API consumers, connector authors, integration engineers
**Last Updated:** 2026-10-17

## Quick Links

| Document | Description |
|----------|-------------|
| [`error-model.md`](./error-model.md) | Canonical error model (RFC 9457 + extensions) — W2 artifact, subject of gate row 5 |
| [`identity-context.md`](./identity-context.md) | Canonical identity context model — W1 artifact, projection of Design 1.29 |
| [`search-model.md`](./search-model.md) | Canonical search resource model — W3 artifact, projection of Design 1.32 §41–46 |
| [`operation-model.md`](./operation-model.md) | Canonical Operation resource + state machine — PAPI-3 artifact; reconciles `ExtractionStatus` and GPU `ExecutionState` |
| [`eventing-contract.md`](./eventing-contract.md) | Eventing 1.27 contract (#247) — event/command, ordering, idempotency, outbox/inbox, dead letters, workflow state |
| [`rest/components/Problem.yaml`](./rest/components/Problem.yaml) | Problem-detail component, `$ref`'d by W3/W4 specs (single source) |
| [`rest/openapi-auth.yaml`](./rest/openapi-auth.yaml) | Authentication API (W4) — AS-facade shape per ADR-018 |
| [`cli/synctl-helper.md`](./cli/synctl-helper.md) | `synctl helper` CLI reference |
| [`cli/synctl-wizard.md`](./cli/synctl-wizard.md) | `synctl wizard` CLI reference |
| [`grpc/spi-guide.md`](./grpc/spi-guide.md) | gRPC SPI implementation guide |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `rest/` | REST API - OpenAPI spec and examples |
| `grpc/` | gRPC SPI guide and proto references |
| `cli/` | CLI command reference |
