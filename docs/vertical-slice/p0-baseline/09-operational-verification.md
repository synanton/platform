# P0-5 — Operational verification

Status: draft for #859. Verification date: 2026-11-24. This records what was actually observable from the accessible cluster and scripts; it does not infer usage from documentation.

## Access and limits

- **Logs:** `kubectl logs` is available for the `synanton` namespace. `synflux`, `synquest`, `syntology`, `relix`, and `ui` pods were queried for the last 24 hours.
- **Metrics:** the `prometheus` deployment in `synanton` is scaled to `0/0`; no live metrics query was available.
- **UI analytics:** no UI analytics/telemetry source was found. View-level usage is therefore **human-only/unknown**, not derivable from this environment.
- **Traffic shape:** application logs are not structured per-endpoint access logs. Absence of a route string is not proof of no use.

## Ingestion

Code paths are active in the local demo/integration flows: `scripts/run-local-real-demo.sh`, `scripts/demo/rak-pipeline-smoke.py`, `tests/integration/vertical-slice.sh`, and the live E2E spec call source creation and ingestion-run endpoints. In the accessible `synanton` cluster, `synflux` logs contained 3 `ingest` lines in the last 24 hours; no structured endpoint access log was available. No UI ingestion consumer exists. **Classification:** active via scripts/E2E and configured live service; external/production ingestion volume is not proven from logs.

## Ontology

Syntology is running in `synanton`; Relix active-version/bootstrap clients and the admin UI consume its endpoints. No structured Syntology request log was visible in the 24-hour window, so live call volume is unknown. E2E tests exercise version creation, graph reads, capabilities, and entity resolution; these are test-driven, not proof of external use. **Classification:** active service with known in-repo consumers; external usage is unverified.

## Extraction

The live `synflux` deployment has `EXTRACTION_CONSUMER_ENABLED=true` and provider `regex`; the consumer, extraction trigger, graph handoff, and status machine are wired. No `extraction_completed` events appeared in the last 24 hours of logs, so recent live extraction volume is not evidenced. Local tests and demos exercise the consumer heavily. **Classification:** active configuration and code path; current production traffic is unknown.

## Graph

Relix is running; the UI Entity Graph route, gateway graph client, and Synquest resolution clients consume it. No `/graph/query`, `/graph/mentions`, `/graph/relations`, or `/v1/entities/.../neighbors` request lines were visible in the 24-hour log window. Live E2E tests exercise entity neighborhood and provenance; graph storage/resolution/promotion are test-covered. **Classification:** active service; actual UI/external graph usage is unverified from logs.

## UI

The UI nginx access log showed 767 `GET /` requests in the last 24 hours, confirming the SPA shell is served. No backend API paths appeared in that log. The UI has no telemetry, so which views were visited is unknown. The packaged deployment also lacks ingress for `/topology`, `/mcp`, and `/search` (P0-2 UI contract), so some documented views cannot work in production as packaged. **Classification:** SPA is served; view-level active use is unknown and partially documented-only.

## Search

Synquest is running and is actively used by local demo/integration scripts and benchmark tooling. No `/v1/search` or legacy `/search` request lines were visible in the 24-hour log window, and Prometheus was unavailable. The UI Chat/search path is broken in the packaged deployment (no proxy/auth/tenant/response match). **Classification:** active service and script/E2E consumption; live production search volume and UI search usage are unverified.

## Decision

Operational verification is **partial by evidence availability**, not by omission:

- Confirmed: services are deployed and running; scripts/E2E exercise ingestion, ontology, extraction, graph, search, and the SPA is served.
- Not confirmed: production request volume, UI view usage, external consumer traffic, and live graph/search/ extraction activity.
- Consequence: P0-5 should be treated as log-and-code verified, with UI analytics explicitly human-only. If the P0 exit gate requires view-level proof, that proof must come from an operator or an analytics source not present in this environment.
