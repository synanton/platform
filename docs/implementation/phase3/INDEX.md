---
title: "Phase 3 - Multi-tenant, Auth, Router"
status: "planned"
last_reviewed: "2026-07-24"
---

# Phase 3 - Multi-tenant, Auth, Router

**Purpose:** Implementation plans for Phase 3 - provisions multiple tenants, wires real credential-based authentication end-to-end, moves ingestion off inline invocation onto Kafka, and exposes a curated tool surface to external MCP clients (e.g. Claude Desktop).
**Audience:** Engineers, leads
**Last Updated:** 2026-07-24

## Theme

> Replace the single-tenant, direct-call, inline-ingest demo with a production-shaped platform: two isolated tenants, API-key auth, Kafka-backed ingestion queue, and an external MCP endpoint that hands back tenant-scoped search results to any MCP-compatible client.

## User-Facing Capability Unlocked

Two tenants (`demo`, `demo2`) see completely disjoint search results from the same platform. Users and services authenticate with API keys. Ingestion jobs are enqueued to Kafka, not executed inline - the ingestion path can now scale workers independently of the API. An external MCP client calling `search` gets back tenant-scoped hits secured by the caller's API key. Admins provision tenants and generate API keys through a control-plane API and admin UI without touching config files.

## Module Plans

| # | Module(s) | Plan | Scope |
|---|-----------|------|-------|
| 01 | `shared/common` EXT · `ingestion-cache` EXT · `synflux` EXT · `synflux-router` NEW · `synanton-llm-client` EXT | [`01-ingestion-pipeline.md`](./01-ingestion-pipeline.md) | Kafka outbox, worker consumer, router service, Anthropic translator |
| 02 | `relix` EXT | [`02-relix.md`](./02-relix.md) | gRPC connector SPI, InMemory→gRPC, Neo4jConnector |
| 03 | `planner` EXT | [`03-planner.md`](./03-planner.md) | Cost estimation, multi-plan generation, cheapest-plan selection |
| 04 | `gateway` EXT | [`04-gateway.md`](./04-gateway.md) | Reranker port, Resilience4j circuit breakers per engine |
| 05 | `synapt` EXT | [`05-synapt.md`](./05-synapt.md) | Per-tenant rate limiting, budget enforcement, ingest enqueue endpoint |
| 06 | `security` EXT | [`06-security.md`](./06-security.md) | RFC 8693 token exchange, identity profiles, API key lifecycle |
| 07 | `topology` EXT | [`07-topology.md`](./07-topology.md) | Mutation API, outbox dispatcher, Neo4j ACL projection |
| 08 | `control-plane` NEW (first real impl) | [`08-control-plane.md`](./08-control-plane.md) | Admin API, ModelServingDirectory |
| 09 | `syntology` EXT | [`09-syntology.md`](./09-syntology.md) | Session pinning, per-tenant versioning, capability matrix |
| 10 | `synanton-mcp` NEW (first real impl) | [`10-synanton-mcp.md`](./10-synanton-mcp.md) | MCP tool surface: search, graph_query, ontology_resolve |
| 11 | `syntology-admin` (UI) EXT | [`11-syntology-admin.md`](./11-syntology-admin.md) | Tenant switcher, admin panel, MCP config panel |

## Phase 3 DoD (Composite)

Per master plan §6:

- Two-tenant demo: `X-Tenant: demo` and `X-Tenant: demo2` return disjoint search results from the same running platform.
- Each tenant has one API key provisioned via `POST /auth/api-keys`; requests authenticated by that key are accepted, others are 401.
- Ingestion is enqueued to Kafka (`ingestion_requests` topic) - `POST /ingest` returns `{ "status": "QUEUED" }` immediately; `synflux` workers consume and execute asynchronously.
- One external MCP client (Claude Desktop configured to `http://localhost:8091/mcp`) calls `search` and receives tenant-scoped hits for the authenticated tenant.
- `control-plane` admin API provisions a second tenant end-to-end without any manual DB intervention.

## External Dependencies Added

- **Kafka** (single-broker, KRaft mode) - first use. Topics: `ingestion_requests`, `ingestion_events`, `ingestion_completed`, `topology_events`.
- **Redis** - first use by `synapt` for budget spend tracking (already in compose plan).
- **Neo4j** - optional; available via `--profile neo4j` compose override. Not required for Phase 3 DoD.

## How to Contribute

Phase 3 plan files follow the naming convention `NN-{module}.md`. When a plan is authored or updated, update this INDEX and the master [`../synanton-phases-plan.md`](../synanton-phases-plan.md) Plan File Inventory (§13). All plans reference `synanton-design-1.19.md` as the authoritative architecture source.
