---
title: "gRPC SPI Implementation Guide"
version: "1.19"
status: "current"
audience: "connector authors, integration engineers"
last_reviewed: "2026-07-21"
---

# gRPC SPI Implementation Guide

**Design reference:** [`../../architecture/synanton-design-1.19.md §28–§32`](../../architecture/synanton-design-1.19.md)

## Overview

The Synanton platform exposes extension points via gRPC Service Provider Interfaces (SPIs). All SPIs use `protoc-gen-validate` (PGV) rules for field validation (added in v1.18).

## Available SPIs

| SPI | Section | Description |
|-----|---------|-------------|
| Relix Graph Connector SPI v1.0 | §28 | Connect external graph databases (Neo4j, Neptune, etc.) |
| Content Adapter SPI | §29 | Ingest content from custom sources |
| WebSearch Adapter SPI | §29 | Federate web search results |
| Reranker Port | §30 | Plug in a custom reranker |
| Identity Provider Port | §31 | Connect an external IdP |
| ACL Propagation Port | §32 | Propagate ACL changes to external systems |

## Validation Requirements (v1.18+)

All SPI implementations must handle PGV `INVALID_ARGUMENT` responses from the platform's `ServerInterceptor`. Field constraints are defined in the `.proto` files - see the source `.proto` files in `../../` for current rules.

## Proto sources

Proto files live in the source tree, not in `docs/`. Reference them via the build system:

```
java/[module]/src/main/proto/
rust/[module]/proto/
```
