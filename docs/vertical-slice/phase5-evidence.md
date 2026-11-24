# Phase 5 — Workflow UI evidence

Status: first implementation slice for #882.

## Implemented journey

`ui/syntology-admin` now has a Workflow route that uses the existing SPA and backend contracts:

1. **Submit** — ensures an `upload` source and posts a multipart ingestion run.
2. **Process** — polls the existing source status until extraction reaches `KNOWLEDGE_READY`/`EXTRACTION_FAILED`.
3. **Results** — reads accepted extraction results and quarantined line reasons from `/v1/extraction/documents`.
4. **Provenance** — shows generation ID/status, ontology release ID/digest, prompt version/digest, and document/source-version identity.
5. **Promote** — explicit per-generation `STAGED → ACTIVE` action via `/v1/extraction/documents/promote`; graph visibility alone does not imply promotion.
6. **Graph** — displays document-scoped mentions/edges and promotion state from the Phase 4 endpoint.

## Backend addition

`ExtractionDocumentController` provides the missing read/promote surface for a source-version document:

- `GET /v1/extraction/documents` with source identity + version.
- `POST /v1/extraction/documents/promote` with explicit generation id.
- Tenant from verified context; no raw quarantine lines returned.

## Evidence

| Claim | Test |
|---|---|
| Read results/quarantine/provenance shape | `ExtractionDocumentControllerTest#returnsResultsQuarantineAndProvenance` |
| Explicit promotion | `ExtractionDocumentControllerTest#promotesKnownGenerationExplicitly` |
| SPA submit→process→results→provenance→promote | `WorkflowPage.test.tsx` |
| Existing SPA views/routes preserved | Existing `App.tsx` routes retained; new route added. |
| UI login/grants repairs | `authApi.ts` accepts `expiresIn`; grants path no longer double-prefixes. |
| Dev/prod ingress for workflow | Vite `/synflux` + `/api/v1/documents`; nginx `/synflux/` + document graph route. |

## Remaining Phase 5 work

- Full existing-view/ingress regression sweep (search UI is Phase 6; topology/mcp/search ingress fixes remain assigned).
- Build All and any UI lint/test gate.
- No F1/gold/gate/profile/ADR-044 claim.
