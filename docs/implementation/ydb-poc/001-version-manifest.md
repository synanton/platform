# YDB-POC-001 — Pinned YDB Server and Java SDK Versions

**Status:** Closed (Phase 0A)
**Date:** 2026-09-26
**Sources:** https://github.com/ydb-platform/ydb/releases, https://github.com/ydb-platform/ydb-java-sdk/releases (checked 2026-09-26)

## Manifest

| Component | Pinned version | Channel | Released |
|---|---|---|---|
| YDB server | `26.3.1.16` | **Pre-release** | 2026-09-21 |
| YDB Java SDK (`ydb-java-sdk`) | `v2.4.11` | Latest stable | 2026-08-31 |

## Image deviation (recorded 2026-09-26)

Pinned server `26.3.1.16` has no pullable image in this environment. Local PoC
runs on **`ydbplatform/local-ydb:stable-26-3-1-path-aliases`** (pulled via
`mirror.gcr.io`, retagged locally; single-node `local-ydb` variant, 26.3.1 line).
Accepted for throwaway-scope PoC runs; re-validate against the exact pinned
server build before any production-track claim (Phase 6).

## Connection findings (021, recorded 2026-09-26)

- The image serves **gRPCS only** (self-signed CA at container
  `/ydb_certs/ca.pem`, CN=localhost); plaintext grpc connects then drops
  (`UNAVAILABLE, Network closed`). Tests use `grpcs://` + container CA trust
  (`docker cp ydb-poc:/ydb_certs/ca.pem …`; `YDB_CA_PATH` override).
- SDK 2.4.11 notes: `executeSchemeQuery` returns `Status` (no `getValue`);
  `SELECT` via the scheme endpoint fails — data queries go through the data
  path. YQL DDL confirmed: ``CREATE TABLE `t` (… Utf8 …, PRIMARY KEY (…))``.
- Modern tx path: `beginTransaction(TxMode.SERIALIZABLE_RW)` →
  `TableTransaction` (per-statement `executeDataQuery`, no-arg `commit()` /
  `rollback()`); the older `Transaction.Mode` API returns the deprecated type.

## TLS scope (021 review answers)

- TLS-only is a **PoC-image configuration** (local-ydb ships grpcs), not a
  proven production constraint. The adapter already accepts CA bytes at
  construction, so a TLS-or-plaintext production surface is a config choice,
  not a redesign.
- `YDB_CA_PATH` handling is **test-only**. Production takes CA material via
  deployment config when 021 hardens; no `DeploymentRequirements` change
  (transport config is not a capability gate).

## Version-coupled assumptions

Consolidated in `version-coupled-assumptions.md` (single list, single
re-validation trigger). Do not add version-coupled notes elsewhere.

## §11.1 schema deviations (021, as built)

Tables carry a per-deployment prefix (`<prefix>_documents|_chunks|_provenance|
_publications`); per-store tenant namespaces isolate tests without DDL churn.

- chunks PK `(tenant_id, doc_id, chunk_ordinal)` (not `(tenant, chunk)`) — ordered
  pagination without a secondary index.
- provenance PK `(tenant_id, doc_id, chunk_id)` (not `(tenant, chunk)`) —
  doc-scoped delete without a secondary index.
- `text` → `chunk_text`, `ordinal` → `chunk_ordinal`, `page` → `page_num`
  (YQL reserved words).
- JSON content in `Json` columns (as §11.1); no DB-side JSON ops
  (`supportsJsonFilters=false`).
- Embeddings as Base64 `Utf8` + `embedding_dim Uint32`; native vector type and
  index syntax pinned in 024B.
- `published_at` nullable (plain `Timestamp`); all other columns `NOT NULL`.

## Notes

- The proposal candidate is YDB 26.3.x, so the server pin follows the 26.3 line. As of this writing the newest 26.3 build (`26.3.1.16`) is marked **Pre-release**; the newest stable is `26.2.1.14` (2026-09-17).
- **Risk carried to YDB-POC-003/036:** evaluating against a pre-release server is a production risk. If 26.3 goes stable during the PoC, re-pin to the stable patch and re-run the benchmark subset (per §13 re-validation rule). If it stays pre-release at Phase 6, the decision package must record that explicitly.
- SDK `v2.4.11` is a routine bugfix release (session autorelease StackOverflow fix); no API break vs `v2.4.x` noted in its changelog.
- Re-validation rule: if either version changes mid-PoC, YDB-POC-001 reopens and the affected benchmark tickets re-run.
