# AAP-5 - Analytics Security

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 5](../../architecture/synanton-design-1.25.md), §26-§29, §44-§47, §54-§56, §79-§80

**Enforces invariants:** 3, 4, 5, 6, 7, 8, 14, 15 - the bulk of the design's security invariants live here.

---

## Goal

Make the Analytics Plane from AAP-4 safe to query: classification propagation into facts, tenant/system scope isolation, aggregate side-channel protection, compile-time-style query sanitisation reusing the SEC-track machinery, and a `test:analytics-security` CI tier with its own negative corpus.

## Work items

1. **Classification propagation** - `analytics` fact-writing path:
   - Every fact derived from protected content computes `derived_classification = max(applicable source classifications)` unless a stricter registered policy applies (design §26) - never less restrictive merely because aggregation removed the literal.
   - Facts from a Masked-only source only ever reference the masked representation (design §27); facts from a Dual source record which representation was used.
2. **Tenant + system scope enforcement** - `analytics` query layer:
   - Every tenant-scoped fact requires non-null `tenant_id`; platform-wide facts use reserved `tenant_id = system` with `platform_scope = SYSTEM` (design §46).
   - A tenant-scoped query must never implicitly expand to `tenant_id = system` - enforced at the query-builder layer, tested with an explicit negative case.
   - Isolation applies at storage, query, cache, API, MCP and materialized-view layers (design §47) - MCP/API enforcement lands in AAP-8 but the underlying query layer built here is what they call into.
3. **Aggregate side-channel protection** - `java/analytics/.../aggregate/AggregatePolicyEngine.java`:
   - Implements the design §28 policy shape (`classification`, `minimum_group_size`, `suppression`, `rounding`, `allowed_dimensions`, `prohibited_dimensions`) as a registered, tenant-strengthenable-only policy (never weakenable).
   - Enforced at query time and/or materialization time for pre-aggregated views.
4. **Query-side sanitisation reuse** - extend `gateway`'s `QuerySanitizer` (built in SEC-6) to cover analytics/report query paths, per design §36 ("Top-search-term reports must apply query-side sanitization consistent with Design 1.23") and §45.
5. **Cache invalidation** - extend the SEC-5/SEC-6 cache-invalidation triggers (security mapping change, source classification change) to also invalidate analytics caches when aggregate policies, metric definitions, or representation policy change (design §56).
6. **Security reclassification handling** - implement the design §54 preferred model: `valid_from`/`valid_to` on historical facts + current-policy evaluation at query time, rather than blanket invalidation, to satisfy audit/compliance needs.
7. **`test:analytics-security` CI tier** - `.github/workflows/analytics-security-tests.yml`, extending the SEC-4 `test:security` pattern:
   - New negative corpus per design §80 (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`, `MASKED-ONLY`, `DUAL`, `ORIGINAL-RESTRICTED`, `SYSTEM-SCOPE`).
   - Assertions per design §79: tenant isolation, classification propagation, masking boundaries, aggregate suppression, cross-tenant restrictions, cache invalidation, report sanitisation, MCP authorisation (stubbed until AAP-8), query-path enforcement, platform-scope isolation.

## Definition of Done

1. A fact derived from a `RESTRICTED`/Masked-only source is `RESTRICTED`-classified even after aggregation removes the literal value (no downgrade-by-aggregation).
2. A query scoped to `tenant_id = acme` never returns or aggregates `tenant_id = system` rows, even when the caller has admin privileges elsewhere (explicit negative test).
3. An aggregate query below `minimum_group_size` returns a suppression marker, not a small-population result; rounding/dimension restrictions are enforced per the registered policy, and a tenant override that would *weaken* a global policy is rejected.
4. Query-side sanitisation strips restricted-pattern search terms from analytics/report responses (reusing SEC-6's `QuerySanitizer`).
5. Changing an aggregate policy or metric definition invalidates the corresponding analytics cache entries within the same SLO class as SEC-5/SEC-6 cache invalidation.
6. A security reclassification (e.g. `CONFIDENTIAL → RESTRICTED`) does not retroactively change historical facts' recorded classification but does change what current-policy queries may return, per the `valid_from`/`valid_to` model.
7. `test:analytics-security` CI job is green on main and blocks PRs that reintroduce a restricted literal into any analytics store or bypass tenant/system scope isolation.

## Key files

| File | Change |
|------|--------|
| `java/analytics/.../classification/ClassificationPropagator.java` | New - derived_classification computation |
| `java/analytics/.../query/TenantScopeGuard.java` | New - system-scope isolation |
| `java/analytics/.../aggregate/AggregatePolicyEngine.java` | New - §28 policy engine |
| `java/gateway/.../query/QuerySanitizer.java` | Extend for analytics/report paths |
| `java/analytics/.../security/ReclassificationHandler.java` | New - `valid_from`/`valid_to` model |
| `.github/workflows/analytics-security-tests.yml` | New CI tier |
| `docs/observability/alerts/analytics-security.yml` | New alert rules |

---

[← AAP-4 Analytics PoC](./04-analytics-poc.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-6 Reporting](./06-reporting.md)
