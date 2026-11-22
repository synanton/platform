# ADR-026: One active canonical graph projection per tenant

**Status:** Accepted — 2026-11-11
**Basis:** #618 identity-scope investigation; the existing canonical graph has tenant-scoped data but no stored `graph_id`/`projection_version`, and generic graph callers have no projection version in scope.

## Decision

1. **One active canonical ontology projection per tenant.** A tenant's canonical graph is a stable logical graph (`graphId = "canonical"`) representing the currently active ontology projection. The active projection version is authoritative graph-scope metadata, not a value each caller invents and not a property duplicated on every canonical node or edge.
2. **`GraphIdentity(tenantId, graphId, projectionVersion)` is the graph operation identity.** Generic graph callers use `GraphIdentity(tenant, "canonical", activeProjectionVersion)` supplied by an authoritative resolver. Resolution-specific reads continue to use the ontology version already present on `:RESOLVES_TO`; that is a resolution filter, not a second canonical-graph identity.
3. **`GraphIdentityResolver` enforces the invariant at the boundary.** Resolving a tenant with zero canonical graphs throws `MissingCanonicalGraphException`; resolving a tenant with more than one throws `AmbiguousCanonicalGraphException`. The single-active-projection invariant is enforced, not merely documented. Generic call sites must not construct `GraphIdentity` manually.
4. **`projectionVersion` is not `projectionGeneration`.** `projectionVersion` identifies the ontology/projection contract used to build the canonical graph. Rebuilds, snapshots, or incremental materializations use a future, distinct `projectionGeneration` concept; they must not be mixed into this field.
5. **No canonical-node backfill for the single-projection model.** Existing tenant-scoped `:Entity`, `:REL`, and `:RESOLVES_TO` data belongs to the tenant's one canonical graph. The resolver maps the tenant to that graph and its active projection version. We do not add `projection_version` to every node/relation unless the multi-projection trigger below is reached.
6. **Ports carry identity, not implicit scope.** `GraphReadPort` and `GraphWritePort` take `GraphIdentity`; adapter queries enforce tenant + graph scope. Version filtering for resolution is applied where the version property already exists. The initial port extraction is behavior-preserving.
7. **Multi-projection is a separate decision.** Supporting multiple concurrent canonical projections, side-by-side upgrades, historical projection inspection/rollback, or caller-selected projection versions requires a new ADR and explicit physical/logical isolation; it is out of scope for this increment.

Consequences:
- **No migration:** existing canonical data remains visible under the tenant's single active projection.
- **No invented versions:** generic call sites receive identity from the resolver; resolution reads use the version they already own.
- **Enforced invariant:** zero or multiple canonical graphs fail fast at the boundary instead of being hidden by a default.
- **Deferred:** versioned canonical graph snapshots and per-node projection identity are explicitly not part of this decision.
- **Rejected:** backfilling `projection_version` onto every canonical node/relation now; it would lock in an unproven multi-version model and require an expensive migration.
- **Rejected:** treating `projectionVersion` as an optional hint with a silent default; that recreates implicit graph scope.

**Companion contract:** [`graph-identity-authority-contract.md`](../planning/graph-identity-authority-contract.md) records the Syntology/Relix ownership and bootstrap semantics.

## Port nomenclature

- `GraphReadPort` / `GraphWritePort` — canonical `:Entity`/`:REL` operations requiring `GraphIdentity`.
- `ExtractedMentionGraphReadPort` / `ExtractedMentionGraphWritePort` — RAKG extraction-pipeline records (`:ExtractedMention`, `:EXTRACTED_RELATION`, relation evidence).
- `ResolutionGraphPort` — resolution and promotion operations.

The bare port names refer to the canonical graph because that is what "the graph" means in this ADR. Extracted mentions and resolution are specializations with their own ports; they must not leak into the generic canonical contract.

## Context

The #618 review found three coupled problems: canonical data has no stored `graph_id`/`projection_version`; generic graph call sites have only a tenant; and the `GraphReadPort`/`GraphWritePort` boundary does not yet exist. Option A (backfill per-node version) would strand existing data until migrated. Option C (default the version) would hide unresolved scope.

The architecture currently implements exactly one canonical graph per tenant. That invariant is sufficient to give every canonical operation a real identity without adding per-node version properties. The resolver is the load-bearing control: if the invariant cannot be proven, the system fails closed rather than returning a partial or defaulted graph.
