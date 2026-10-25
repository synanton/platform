# Tenant isolation acceptance — layer by layer

**Status:** PASS for every layer in scope (2026-10-25). Aggregate "tenant
isolation passed" is still not the claim: each row below has its own evidence.
#340 token issuance and #421 remain separate.
**Primary acceptance:** reads from the primary. Replica read path is validated
separately by `ReplicaReadParityTest` against a locally provisioned streaming
replica.

## Evidence metadata

| Item | Value |
|---|---|
| Image/code commit (search/provenance) | `7d3484d` (synquest `sha256:ebbdd6fe…`) |
| Image/code commit (replica) | `5f98c95` |
| Acceptance tests | `c519cee` / #427, #429 |
| Core corpus hash | `b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e` |
| CI | #429 Build `40215474722`, Real PDF `40215474816` |
| Replica | local streaming primary `:55432`, replica `:55433`, same `app` role |


| Layer | Control | Positive | Negative | Evidence / status |
|---|---|---|---|---|
| Relational content cache (tenant) | `TenantScopedContentStore` + transaction-local `app.tenant_id` + FORCE RLS | Tenant A reads A | Tenant B reads A → zero/not-found | PASS: `JdbcContentStoreTest` 12, `TenantScopedContentStoreTest` 5, rebuilt-image A/B run |
| Relational content cache (service) | compiled capability + facade | capability `{A,B}` reads both | capability absent `{A}` → zero rows, not error | PASS: `ServiceCapabilityTest` (multi-tenant, absent, attribution) |
| Cross-tenant partial failure | N per-tenant transactions + `PartialReconcileException` | A commits while B fails; outcomes per tenant | retry same `operationId` does not duplicate A audit | PASS: `partialFailureCarriesPerTenantOutcomesAndRetryDeduplicatesAudit` |
| Audit integrity | same-transaction audit; denial counter | allowed audit row with row count | audit failure aborts allowed read; denial failure zero rows + counter | PASS: allowed/denial/mutation audit failure tests |
| Permissioned partial/full scope | max 100 per call, enumerated, no wildcard | `{A,B}` two calls still bounded | `*`/`ALL`/empty/101 rejected | PASS: wildcard/oversize test |
| Search projection | snapshot-scoped tenant filter | A query returns A (20 results, fact top `c799bd65…:5`) | Unknown/absent tenant → 404; known tenant with canonical data but no projection → 503 + `Retry-After: 5`; B no data → 404 | PASS: rebuilt image `7d3484d`, synquest `sha256:ebbdd6fe…`; observed after #421 |
| Provenance | tenant-scoped identity read | A provenance 200, source URI `manual-upload://mental-health-report-2010/mental-health-report-2010.pdf` | B provenance 404; unknown tenant 404 | PASS: rebuilt image `5f98c95` |
| Service principal negative | no cross-tenant capability | — | zero rows, not error | PASS: capability-absent test |
| Service principal positive | enumerated capability | audit line per tenant | unlisted tenant zero | PASS: multi-tenant + attribution tests |
| Replica read path | same app role + FORCE RLS + local setter | A and B non-zero on replica; each sees only its own artifact; primary/replica parity | unknown tenant zero on replica (RLS active) | PASS: `ReplicaReadParityTest` against local streaming replica (primary :55432, replica :55433); A/B/unknown observed. One-time acceptance, not continuous; CI provisioning tracked in #428 |
| Cross-tenant audited reconcile on replica | same-transaction audit | — | — | N/A by design: read-only replica cannot accept the audit write; reconcile stays primary, replica covers the read layer |
| HTTP/token issuance | #340 | token → `ServicePrincipal` | no capability grant in token path | NOT IN #339 |

## Replica execution record

- Local streaming primary (`:55432`) and replica (`:55433`), same `app` role
  with FORCE RLS and transaction-local `app.tenant_id`.
- `ReplicaReadParityTest` passed: A and B had known artifacts on the replica;
  unknown tenant saw zero for the same digest; A could not see B's digest and
  B could not see A's; primary/replica counts matched.
- The audited cross-tenant reconciler was not run on the read-only replica; it
  stays on the primary because the audit row must commit with the read. The
  replica row proves the read-layer RLS and cross-tenant attribution.

## Replica validation policy

The replica row is a **one-time local acceptance** (2026-10-25), not continuous
CI coverage. Re-run it when the replica role, pool configuration, FORCE RLS, or
topology changes. CI provisioning is tracked in #428; until that lands, a green
build does not prove replica RLS.

## Open gaps

- #340 token issuance and the browser/UI path remain separate.
- #421 (unknown-tenant search 503 vs provenance 404) is a separate client-visible
  follow-up and does not block the layer rows above.
- The replica test is env-gated for repeatability; the actual local run above
  had a replica provisioned, so it is not a skipped row.
