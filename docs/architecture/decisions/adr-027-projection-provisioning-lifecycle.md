# ADR-027: Projection provisioning is a separate lifecycle from runtime graph access

**Status:** Accepted — 2026-11-12
**Basis:** Gate E2 identity review found a runtime `"demo"` tenant fallback in k-hop traversal and a startup projection load with no `GraphIdentity`. These are different problems: runtime access may only consume an established identity, while provisioning may establish it.

## Decision

1. **Runtime graph operations consume `GraphIdentity`.** Read/write call sites obtain identity from `GraphIdentityResolver.resolveCanonical(tenantId)`. Missing, blank, or mismatched tenant identity fails closed before graph access. No operation substitutes a default tenant.
2. **Projection provisioning is a distinct lifecycle.** Provisioning takes an explicit tenant plus provisioning context, not a runtime `GraphIdentity`. Its purpose is to create or initialize the tenant's canonical projection and establish its `:CanonicalGraphMetadata`.
3. **Provisioning may establish identity; it may not bypass identity for runtime access.** Provisioning writes metadata only under the bootstrap rules: authoritative Syntology version, additive data load, unique tenant constraint, concurrent `MERGE`, retry-once on write conflict, and no overwrite of an existing `projection_version`. Runtime reads/writes still go through the resolver.
4. **Authority and failure behavior are explicit.** Syntology is authoritative for the active ontology version. `404` means no active ontology; `503`/timeout is retryable; neither permits a default version. Partial projection loads are additive and retry-safe; metadata is never fabricated from graph data.
5. **Provisioning is explicitly initiated and authorized.** The initiator is an operator-controlled startup job or an admin provisioning operation using a provisioning-capable service identity. Request-scoped runtime callers cannot invoke provisioning implicitly. The current `RelixApplication` demo projection load is a local/test provisioning path only, never a runtime identity source.
6. **Existing metadata is not "repaired" by provisioning.** A version change is an ontology migration, not a bootstrap overwrite. Provenance of the metadata (`synced_at`, `created_at`, `updated_at`) remains as defined in the authority contract.

Consequences:
- **Runtime:** missing identity rejects before any graph read/write; no `"demo"` or other default tenant.
- **Provisioning:** may bootstrap missing metadata under the existing concurrency-safe, no-overwrite rules.
- **Implementations:** E2c migrates runtime call sites to `GraphReadPort`/`GraphWritePort` + `GraphIdentityResolver`; E2b implementation may add an explicit provisioning entry point without exposing it on runtime paths.
- **Rejected:** treating startup `loadTenant("demo")` as a runtime graph operation; that conflates creating a projection with consuming one.
- **Rejected:** giving provisioning a synthesized `GraphIdentity` before metadata exists; identity must be established by provisioning, not invented by it.

## Context

ADR-026 makes `GraphIdentity(tenantId, graphId, projectionVersion)` the identity of generic canonical graph operations. The Gate E2 audit found two pre-identity paths: `KHopPathExecutor` silently substituted tenant `"demo"` when the request had none (an isolation defect fixed in E2a), and startup projection loading calls a tenant-only load before metadata exists. The first is a runtime fail-closed bug; the second is a legitimate provisioning concern that must remain separate. The authority contract's Syntology lookup, additive bootstrap, unique constraint, and no-overwrite rules apply to provisioning.
