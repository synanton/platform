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

**principal : tenant is many-to-many** (Design 1.29 §37). A principal may
belong to one or more tenants where the deployment model permits. A request
therefore binds an explicit **(principal_id, tenant_id) pair** — not a
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

## Naming note

Design 1.29 uses `principal_id` / `principal_type`. The Gen-A implementation
(`McpIdentityContext`, `VerifiedSecurityContext`) names the principal
`subjectId`. These are the same concept; whether the external contract uses
`principal_id` (1.29) or `subject_id` (implementation) is a naming decision to
record, not to diverge on silently — same shape as the W3 `results`/`hits`
flag.
