# Source lifecycle fixture

**Status:** #248 implementation slice with tenant-scoped Source, run, and Operation routes; durable recovery follows #250
**Contract:** [`../api/source-model.md`](../api/source-model.md)

The deterministic fixture flow uses the existing filesystem `ContentAdapter`,
the Content Cache `ContentStore` port, the normal Synflux
acquisition/extraction/chunk/persist stages, and the Cassandra-backed
`ingestion-cache` source lifecycle records. `FixtureIngestionService` owns
per-resource history; `FixtureSourceWorkflow` connects it to source/run
submission and the public Operation record.

## Local reproduction

With Docker available to Testcontainers, run:

```bash
./gradlew :java:content-cache-api:test :java:ingestion-cache:test :java:synflux:test --no-configuration-cache
```

The test installs the real Cassandra schema migration and exercises the same
service and public fixture workflow. It covers first/unchanged/changed ingestion,
distinct source identities with identical bytes, tenant isolation,
authoritative deletion and tombstone provenance, incomplete discovery, a
post-deletion re-creation, revision conflicts, failure/retry, cancellation, and
empty-manifest terminal cases. REST tests verify tenant isolation on source, run,
and Operation reads/submissions. The end-to-end workflow test discovers and
reads a real temporary file, exercises acquisition/extraction/chunk/persist
stages, and checks Operation idempotency and outbox deduplication.

## Lifecycle behavior

- The fixture's configured `source_id` is stable across files; each normalized
  connector-relative path is its `resource_id`. The source catalog is keyed by
  `(tenant_id, source_id)`; resource identities and versions are scoped by
  tenant, connector, source, and resource.
- The filesystem connector declares modification-time + size as its strongest
  available version signal. If modification time is absent, SHA-256 is used as
  the explicit fallback. SHA-256 of acquired bytes is always persisted as the
  content digest, separately from source identity and version identity.
- `AcquireStage` publishes raw source bytes through `ContentStore.put`, retains
  the returned opaque ContentReference, then reads the bytes back through that
  reference and verifies tenant, SHA-256, and size before extraction. The
  reference is persisted on SourceVersion and the ingestion manifest. Chunk and
  annotation stages continue to use the established internal `contentRefId`.
  An integrity failure after resource discovery produces a per-resource
  `QUARANTINED` manifest entry and non-retryable Operation error; it does not
  publish a SourceVersion or SourcePublished event.
- The local reference adapter is `FileSystemContentStore` from content-cache-api;
  set `SYNFLUX_CONTENT_CACHE_BACKEND=filesystem` and
  `SYNFLUX_CONTENT_CACHE_ROOT` for local Synflux. Its cache root must be separate
  from `SYNFLUX_FIXTURE_ROOT`. A deployment can provide a ContentStore bean over
  the Content Service adapter. ExtractionStage's ObjectStorePort write remains
  a temporary handoff to the extraction gateway; PersistStage no longer treats
  an S3 path as the canonical source artifact.
- Source-version IDs are deterministic UUIDv5 values derived from full source
  identity, the selected version signal, and (after deletion) a new incarnation
  ID. Cassandra `IF NOT EXISTS` writes preserve immutability and make duplicate
  delivery converge. Reuse of one revision signal for different bytes is a
  non-retryable W2 `RESOURCE_CONFLICT`.
- `ContentStore.put` produces the opaque Content Cache reference before
  extraction; downstream Synflux stages consume the bytes read back through that
  reference. The SourceVersion is made authoritative only after the normal
  processing/persist callback succeeds, and stores both the Content Cache
  reference and the existing internal `content_ref_id` used by chunk indexes.
  A blank or missing reference is a publication-blocker: the resource is
  recorded `QUARANTINED` (non-retryable) and no SourceVersion or outbox event
  is created — every published SourceVersion carries a resolvable opaque
  reference, regardless of which ingest entry point produced it.
- `SourcePublished` and `SourceDeleted` event envelopes are stored atomically
  with their immutable SourceVersion/tombstone rows. Their event IDs are
  deterministic and the event publication flag is transport metadata; duplicate
  source delivery cannot create a second event. The dispatcher/recovery workflow
  remains in #250.
- Only `DeletionSignal.DELETED` creates a tombstone. `SYNC_INCOMPLETE`,
  `NOT_VISIBLE`, `NOT_FOUND`, `ACCESS_DENIED`, and `UNKNOWN` are retained as
  skipped outcomes. Tombstones retain the verified principal and a resolvable
  version-ID manifest; re-creation uses a distinct incarnation without deleting
  old versions or tombstones.
- `IngestionRun.status` and `SyncResult` are persisted independently of the
  linked `operation_id`. Errors are stored as RFC 9457/W2 fields and referenced
  from both the run and its manifest entry.
- The public Operation is created with PAPI-3 states, and terminal state/outcome
  follows the frozen IngestionRun mapping. `Idempotency-Key` is scoped by
  tenant, principal, and endpoint; a retry returns the same Operation and run.
- `/v1/ingestion-runs` accepts one selected connector-relative `resource_id`
  per run, returns the run and Operation IDs, and executes the fixture workflow
  on the ingestion executor. Operation GET/cancel and run GET use verified tenant
  scope. Source creation and Source CRUD/version routes are under `/v1/sources`.

Migrations V9 and V10 create the tenant-scoped Source catalog,
per-resource SourceIdentity rows, immutable source versions, tombstones and
version manifests, ingestion runs/manifests/entries, W2 error records,
Operations, command idempotency rows, and opaque Content Cache reference
columns on SourceVersion and the content manifest. Version and tombstone event
envelopes are co-located in their respective history rows to preserve the
artifact-before-publication ordering.

## Integration boundary and follow-up

Synflux REST routes use the shared `TenantContextFilter` and fail closed unless
the verified JWT provides both tenant and subject. The legacy `/ingest` routes
also derive tenant exclusively from that context; body/query tenant fields and
`X-Tenant` are ignored. The local CLI requires an explicit `--tenant`. Set
`SYNANTON_JWT_SECRET` (at least 32 characters, matching the token issuer) before
starting Synflux; there is no development tenant or signing-secret fallback.
Set `SYNFLUX_FIXTURE_ROOT` to the explicitly mounted filesystem fixture root;
Source endpoints outside that real path are rejected, so tenant configuration
cannot use the fixture connector to read arbitrary host paths.

`SynfluxCli --cli` is a process-level operator tool, not a tenant-user interface.
It does not authenticate an HTTP subject or populate `TenantContext`; the local
operator explicitly selects `--tenant`, and the runner receives that scope as a
trusted process argument. Public tenant requests never use this path.

`ExtractionStageRealGatewayTest` remains an existing manual-only test: it needs
Docker CLI plus a sibling `content_extractor` checkout, while the configured
Testcontainers client cannot negotiate this environment's Docker API version.
It is unrelated to V9 and is excluded with `@Disabled` from the default test run.

The public slice includes Source create/get/list/patch/delete, per-resource
delete, ingestion-run submit/get, and Operation get/cancel routes. Source
configuration deletion is non-cascading; resource deletion writes the
per-resource tombstone and SourceDeleted event. The fixture run request targets
one discovered resource by connector-relative `resource_id`; #250 owns durable
multi-step recovery and dispatch retries. The executor is in-process; after a
process restart, #250's durable workflow is needed to resume a non-terminal run.
