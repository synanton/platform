# Phase 5 — Existing-view and ingress regression evidence

Status: draft for #882. This records compatibility evidence and explicit deferrals; it does not claim MCP/search/chat are demo-ready.

## Existing SPA views

`ui/syntology-admin/src/App.tsx` retains:

- Dashboard
- Ontology Viewer
- Entity Graph
- Admin
- Grants
- MCP
- Chat
- Settings

The new Workflow route is additive. `Layout.test.tsx` asserts every existing navigation entry plus Workflow remains present, and that the tenant display is JWT-bound.

## Ingress inventory

| Path | Target | Status |
|---|---|---|
| `/auth/` | security | existing |
| `/api/` | syntology | existing |
| `/api/v1/entities/*` | relix | existing |
| `/api/v1/documents/*` | relix | added in Phase 5 |
| `/topology/` | topology | added in Phase 5 |
| `/synflux/` | synflux | added in Phase 5 |
| `/mcp` | synanton-mcp | **deferred / no service in base compose** |
| `/search` | synapt | **deferred to Phase 6 (envelope mismatch; Chat lacks auth)** |

## Tenant/security evidence

- `ApiKeyTenantRequiredTest#listAndRevokeAreScopedToTheCallersTenant`
- `ApiKeyTenantRequiredTest#listWithoutVerifiedCallerRejects`
- `ApiKeyTenantRequiredTest#revokeWithoutVerifiedCallerRejects`
- Workflow/extraction/document graph APIs are tenant-context scoped.
- SPA no longer has a local tenant override or `"demo"` fallback.

## Deferred boundary

MCP, search, and Chat remain existing views but are not advertised as working demo paths. Search/Chat are Phase 6 work; MCP ingress requires an explicit deployed MCP target. This is a deferral, not a compatibility claim.

No F1/gold/gate/profile/ADR-044 change.
