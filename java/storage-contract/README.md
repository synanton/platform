# storage-contract (YDB-POC-037)

Shared port-types home for the Synvault/Synquest ports (proposal §8.2, §8.4, §9.1, §10.1).

- `org.synanton.storage.contract` holds cross-port types used by both `synvault-api`
  and `synquest-api`: `SecurityContext`, `TenantScope`, `PrincipalRef`, `PolicyContext`,
  `DocumentId`, `ChunkId`, `GenerationId`, `SourceVersionId`, `VersionSeriesId`,
  `EmbeddingModelRef`, `StorageErrorKind`, `StorageException`, `PageRequest`.
- Both `*-api` modules (created in YDB-POC-011) depend on this module; neither may
  depend on the other. This module itself has **no provider imports** (no CQL/YQL,
  no Cassandra/YDB SDKs) — enforced by `ProviderIsolationTest` until the ArchUnit
  rule lands in YDB-POC-012.
