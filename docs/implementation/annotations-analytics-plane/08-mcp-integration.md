# AAP-8 - MCP / External Integration

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 8](../../architecture/synanton-design-1.25.md), §42-§44, §88

**Enforces invariants:** 13 (no bypassing the canonical query pipeline), 7-8 (tenant/system scope apply to MCP too).

---

## Goal

Expose selected Analytics Plane capabilities through a public Analytics API (fronted by `synapt`, mirroring how it fronts `gateway` today) and through `synanton-mcp`, with every request passing through the same canonical authentication → tenant resolution → authorization → classification filtering → representation selection → query sanitisation → aggregate protection → result sanitisation pipeline as every other interface (design §44). MCP and the API are interfaces, never a security bypass.

## Work items

1. **Analytics API surface** - `synapt`:
   - New route group `/analytics/*` proxying to the `analytics` module's `MetricController`/`ReportController` (AAP-6), reusing `synapt`'s existing JWT/API-key auth, rate limiting, and quota machinery (design §42).
   - Result sanitisation at the `synapt` boundary in addition to AAP-5's internal sanitisation (defence in depth, matching the existing `gateway`→`synapt` pattern).
2. **Canonical query pipeline wiring** - ensure every Analytics API/MCP call is composed from the same pipeline stages built in AAP-5, not a parallel path:
   ```
   Request → Authentication → Tenant Resolution → Authorization → Classification Filtering
   → Representation Selection → Query Sanitization → Aggregate Protection
   → Metric/Report Query → Result Sanitization → Response
   ```
   Implement as a single `AnalyticsQueryPipeline` component in `java/analytics` that both `synapt`'s HTTP handlers and the MCP tool handlers call into - no independent reimplementation in either caller (design §44, §88's "Internal Contracts" separation).
3. **MCP tools** - `java/synanton-mcp/.../tools/`:
   - `get_metric`, `query_report`, `inspect_analytics`, `explain_metric`, `retrieve_lineage` (design §43).
   - Auth via the existing API-key mechanism from `security` (same pattern as the `search`/`graph_query`/`ontology_resolve` tools from Phase 3).
   - Each tool handler calls `AnalyticsQueryPipeline` directly - it does not call ClickHouse or the registry tables itself.
4. **Lineage retrieval** - `retrieve_lineage` walks the chain from design §37: `Source → ECM Element → Chunk → Annotation → Processing Run → Knowledge Projection → Analytics Event → Analytical Fact → Aggregate → Metric → Report`, returning as much of the chain as the caller is authorized to see (partial lineage, not an all-or-nothing failure, when authorization stops midway).
5. **`explain_metric`** - returns the metric's registered definition (version, source facts, dimensions, aggregation, freshness, security policy) from the AAP-6 registry - governance metadata, not raw data.

## Definition of Done

1. `GET /analytics/metrics/{id}` and `GET /analytics/reports/{id}/data` via `synapt` enforce the same tenant/aggregate/classification rules as calling the `analytics` module directly - a test compares responses for the same caller through both entry points expecting identical authorization outcomes.
2. All five MCP tools (`get_metric`, `query_report`, `inspect_analytics`, `explain_metric`, `retrieve_lineage`) are registered and reachable via the existing MCP STREAMABLE_HTTP transport with API-key auth.
3. An MCP `query_report` call from an unauthorized tenant is rejected identically to an unauthorized `synapt` REST call (same pipeline, same denial) - explicit negative test.
4. `retrieve_lineage` for a fact the caller is only partially authorized to see (e.g. authorized for the metric/report but not the underlying chunk-level annotation) returns the authorized prefix of the chain, not a blanket error.
5. `explain_metric` never returns raw fact data - only registry metadata - verified by asserting its response schema excludes any row-level field.
6. Negative-security corpus from AAP-5 (§80) re-run against both the Analytics API and MCP surfaces, extending the `test:analytics-security` CI tier's coverage to these two new entry points (design §79's "MCP authorization" and "query-path enforcement" checks).

## Key files

| File | Change |
|------|--------|
| `java/analytics/.../pipeline/AnalyticsQueryPipeline.java` | New - single canonical pipeline |
| `java/synapt/.../controller/AnalyticsProxyController.java` | New - `/analytics/*` route group |
| `java/synanton-mcp/.../tools/GetMetricTool.java` | New MCP tool |
| `java/synanton-mcp/.../tools/QueryReportTool.java` | New MCP tool |
| `java/synanton-mcp/.../tools/InspectAnalyticsTool.java` | New MCP tool |
| `java/synanton-mcp/.../tools/ExplainMetricTool.java` | New MCP tool |
| `java/synanton-mcp/.../tools/RetrieveLineageTool.java` | New MCP tool |
| `.github/workflows/analytics-security-tests.yml` | Extend coverage to MCP/API surfaces |

---

[← AAP-7 Production Hardening](./07-production-hardening.md) · [Back to INDEX](./INDEX.md)
