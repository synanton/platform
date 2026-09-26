# YDB-POC-038 — Observability Contract (closed)

**Status:** Closed (taxonomy + wiring; relay/freshness end-to-end lands in 029)
**Date:** 2026-09-26
**Proposal ref:** §8.3

## Taxonomy (frozen, provider-neutral)

Port verbs only — backends differ behind the port, never in names:

`synvault.put/get/delete/chunks/revision/provenance`,
`synquest.search/upsert/delete/rebuild`.

Types (all in `storage-contract`, shared with 039/029):

| Type | Role |
|---|---|
| `AdapterMetrics` (+ verb constants, `Timing` helper) | record per-op latency + outcome |
| `OpStats` | count/errors/avg/max + p50/p95/p99 (bounded 1024 reservoir) |
| `AdapterStats` | snapshot keyed by verb, tagged with provider id |
| `NoopAdapterMetrics` | adapter default until a deployment injects a real impl |
| `InMemoryAdapterMetrics` | dev/test default |
| `FreshnessTracker` / `InMemoryFreshnessTracker` / `FreshnessLag` | commit→visible pairing; relay wiring in 029 |
| `ActiveProviders` | typed active-provider snapshot (shared with 039) |

## Wiring (this batch)

- In-memory adapters default to a live `InMemoryAdapterMetrics` (dev-visible out
  of the box); every port op records timing + success (verified by
  `AdapterMetricsWiringTest`).
- Cassandra adapter defaults to `NoopAdapterMetrics` with an injectable ctor;
  failed paths (UNSUPPORTED revision/delete) record errors, not silence.
- Health: `SynquestIndexAdmin.status()` + `ActiveProviders.describe()` cover the
  §8.3 build-status and provider-identity rows; dashboards consume these types.

## Explicitly deferred (not silent)

- Micrometer-backed implementation for deployments (taxonomy unchanged).
- Relay-side `FreshnessTracker` wiring + lag gating → **029** (already depends
  on 038). Publication-backlog depth/oldest-record-age are relay metrics and
  land with the relay, not here.
