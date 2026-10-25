# Tenant isolation acceptance — layer by layer

**Status:** In progress. #339 is not closed until every row below has evidence;
aggregate "tenant isolation passed" is not sufficient.
**Primary acceptance:** reads from the primary. Replica read path is validated
separately by `ReplicaReadParityTest` when a replica is configured.

| Layer | Control | Positive | Negative | Evidence / status |
|---|---|---|---|---|
| Relational content cache (tenant) | `TenantScopedContentStore` + transaction-local `app.tenant_id` + FORCE RLS | Tenant A reads A | Tenant B reads A → zero/not-found | PASS: `JdbcContentStoreTest` 12, `TenantScopedContentStoreTest` 5, rebuilt-image A/B run |
| Relational content cache (service) | compiled capability + facade | capability `{A,B}` reads both | capability absent `{A}` → zero rows, not error | PASS: `ServiceCapabilityTest` (multi-tenant, absent, attribution) |
| Cross-tenant partial failure | N per-tenant transactions + `PartialReconcileException` | A commits while B fails; outcomes per tenant | retry same `operationId` does not duplicate A audit | PASS: `partialFailureCarriesPerTenantOutcomesAndRetryDeduplicatesAudit` |
| Audit integrity | same-transaction audit; denial counter | allowed audit row with row count | audit failure aborts allowed read; denial failure zero rows + counter | PASS: allowed/denial/mutation audit failure tests |
| Permissioned partial/full scope | max 100 per call, enumerated, no wildcard | `{A,B}` two calls still bounded | `*`/`ALL`/empty/101 rejected | PASS: wildcard/oversize test |
| Search projection | snapshot-scoped tenant filter | A query returns A | B query → zero results | **PENDING**: re-run against frozen `TenantScope` on rebuilt image |
| Provenance | tenant-scoped identity read | A provenance 200 | B provenance 404, never reads A | PASS at unit/controller level; **PENDING** rebuilt-image row |
| Service principal negative | no cross-tenant capability | — | zero rows, not error | PASS: capability-absent test |
| Service principal positive | enumerated capability | audit line per tenant | unlisted tenant zero | PASS: multi-tenant + attribution tests |
| Replica read path | same app role + FORCE RLS + local setter | known tenant non-zero on replica | unknown tenant zero on replica (RLS active) | **PENDING**: env-gated `ReplicaReadParityTest` skipped; no replica provisioned |
| HTTP/token issuance | #340 | token → `ServicePrincipal` | no capability grant in token path | NOT IN #339 |

## Open gaps

- Search projection and image-level provenance need a final rebuilt-image run
  after the service-capability code.
- Replica parity test is parameterized but skipped until a replica URL/role is
  provisioned; do not claim replica evidence before it runs.
- #421 (unknown-tenant search 503 vs provenance 404) is a separate client-visible
  follow-up and does not block the layer rows above.
