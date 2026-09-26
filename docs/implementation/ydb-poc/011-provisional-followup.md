# YDB-POC-011 — Provisional API Surfaces (YDB-POC-010 follow-up)

**Status:** Open — re-validated when 1.27 / 1.32 freeze (then this file closes)
**Gate:** YDB-POC-010 (throwaway scope until freeze confirmed)

Per §0.1, Phase 0B proceeds while 1.27/1.32 are unfrozen, but the surfaces below are
explicitly **not** committed domain APIs. Each carries `@Provisional` in code.

## Provisional inventory

| Surface | Module | Binds to | Status at freeze |
|---|---|---|---|
| `PublicationIntent` (event payload shape) | `synvault-api` | 1.27 event schema | May change with no deprecation; relay in §12.2 re-targeted |
| `StorageErrorKind` (error taxonomy) | `storage-contract` | 1.32 Operation/error contract | Kinds may be renamed/merged/extended |
| `StorageException` (error shape) | `storage-contract` | 1.32 Operation/error contract | Shape may change with the taxonomy |
| `ProviderIncompatibleException` (startup-error shape) | `storage-contract` | 1.32 Operation/error contract | Shape may change with the taxonomy; specificity requirement (provider + capability + reason) is stable |

## Stable (port-owned, not provisional)

`Document`, `Chunk`, `ProvenanceRecord`, `RevisionMetadata`, `DocumentRevision`,
`DocumentWriteOptions`, `RevisionWriteOptions`, `ChunkQuery`, `ChunkPage`,
`StoreCapabilities`, `ConsistencyLevel`, `SynvaultStore`, and the entire
`synquest-api` port (`SearchRequest`, `EligibilityConstraints`, `TemporalExtension`,
`RelevanceFilters`, `ChunkProjection`, `SearchResult`, `SearchCapabilities`,
`SynquestEngine`, `SynquestIndexWriter`, `SynquestIndexAdmin`) — within-port ownership,
no 1.27/1.32 binding.

## Close criteria

When Architecture confirms frozen 1.27 + 1.32 contracts: re-validate each row, remove
or reshape the annotation, and close YDB-POC-010 (PoC re-scoped from throwaway to
production-track by explicit decision).
