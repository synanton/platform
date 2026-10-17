# Identity Context Model

**Status:** Draft — W1 artifact (WS0 contract/governance)
**Source:** Design 1.29 §12–§14 (Principal), §34–§38 (actor chain, identity
context, trust boundary, tenant membership/context)

The canonical identity context the Platform API binds to every request. W1 is a
**projection** of Design 1.29, not a new model — it freezes the subset the
1.32 external surface consumes. Design 1.29 remains authoritative for the full
identity plane.

## Identity context

Design 1.29 §35:

```yaml
identity_context:
  principal_id: ...
  principal_type: ...
  tenant_id: ...
  authentication_assurance: ...
  session_id: ...
  trace_id: ...
```

| Field | Type | Gen-A | Notes |
|---|---|---|---|
| `principal_id` | string | **yes** | stable within Synanton; e.g. `user:01...` |
| `principal_type` | enum | **yes** | `USER` \| `SERVICE` \| `WORKLOAD` \| `CONNECTOR` \| `AGENT` \| `SYSTEM` |
| `tenant_id` | string | **yes** | the explicitly-selected tenant context |
| `authentication_assurance` | string | reserved (Gen-B) | assurance level of the authentication event |
| `session_id` | string | reserved (Gen-B) | |
| `trace_id` | string | reserved (Gen-B) | correlates with request logging |

Gen-A carries the three identity fields; the rest are reserved additive
properties.

## Principal

The canonical identity object (Design 1.29 §12):

```yaml
Principal:
  principal_id: ...
  type: USER
  status: ACTIVE
  tenant_id: ...
  display_name: ...
  external_identities: [...]
  assurance_level: ...
```

The identity context carries the projection relevant to a request —
`principal_id`, `principal_type`, `tenant_id` — not the full principal record.
External identities, display name, and lifecycle status are resolved by the
identity plane, not carried in the request context.

## Cardinality

Tenant membership is **explicit for every tenant-scoped principal** (Design
1.29 §11.5). **Users may belong to one or more tenants** where the deployment
model permits (§37) — a user principal is many-to-many with tenants. Service /
workload / connector principals are ordinarily single-tenant (a search-service
identity is not a member of arbitrary tenants); the design does not generalize
many-to-many to them.

A request binds an explicit **(principal_id, tenant_id) pair** — not a
principal alone, and not a tenant alone.

Tenant selection is **authorized**, not claimed (§38): a client cannot set
`tenant_id = tenant-B` and gain access. The identity system must establish the
selected tenant is one the principal is entitled to.

## Actor chain

For asynchronous work, identity context preserves an actor chain
(Design 1.29 §34):

```yaml
actor:
  initiator: user:123
  delegator: service:api
  executor: service:workflow
```

The exact representation may vary; **semantic preservation is mandatory**.
Gen-A is synchronous (login, search, MCP search), so the chain is single-hop;
the model reserves the chain for Gen-B's async Operations (PAPI-3).

## Trust boundary

Identity becomes trusted only after crossing a trusted authentication boundary
(Design 1.29 §36):

```text
Untrusted Request → Authentication Gateway → Validated Identity Context → Internal Services
```

Two invariants follow, and they are non-negotiable:

1. **Never trust client-supplied identity context** (§35). `principal_id`,
   `principal_type`, and `tenant_id` are never read from a request body or a
   client header.
2. **Internal services validate the provenance** of propagated context (§36).

This is the same rule enforced by ADR-016 (service identity, no bearer
passthrough) and ADR-019 (signed short-lived context for downstream trust).

## Gen-A vs Gen-B

| Gen-A | Gen-B (reserved, additive) |
|---|---|
| `principal_id`, `principal_type`, `tenant_id` | `authentication_assurance`, `session_id`, `trace_id` |
| single-hop context | actor chain for async Operations |

## Naming — external `principal_id`, internal `subjectId`

Design 1.29 is the semantic authority for the principal model; 1.32 exposes it
as the public contract (1.29 §35; 1.32 §126: "Design 1.29 remains the semantic
authority ... 1.32 does not redefine that model — it exposes it").

- **External contract:** `principal_id` / `principal_type` (the 1.29 names).
- **Internal carriers:** `McpIdentityContext` / `VerifiedSecurityContext` use
  `subjectId` — internal implementation, not the external contract.
- **The adapter maps** `subjectId` ↔ `principal_id` at the API boundary.

This is a projection mapping, not an open question: the external surface speaks
1.29's vocabulary; internal code keeps its own. No divergence to record —
the boundary is where the mapping happens.
