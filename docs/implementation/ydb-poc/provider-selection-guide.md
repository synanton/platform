# Provider selection + startup validation guide

Module: `java/storage-provider` (`org.synanton.storage.provider`).
Shared types: `ActiveProviders`, `ProviderIncompatibleException` (`storage-contract`).

## Interaction

```java
ProviderSelection sel = new ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory");
DeploymentRequirements req = DeploymentRequirements.fullRevision(false); // revision+delete, non-prod
ProviderRegistry.ValidatedSelection ok =
    registry.validate(sel, req);   // throws ProviderIncompatibleException on mismatch
ok.describe();                     // "admin=.., synquest=.., synvault=.., writer=.." (sorted, stable)
ok.activeProviders();              // typed snapshot for 038 dashboards
```

`DeploymentRequirements.metadataOnly(production)` for paths without revision/delete.

## What "reject incompatible" looks like

```
provider 'cassandra' incompatible: missing [synvault.revision]. deployment requires
revision semantics (putDocumentRevision atomicity) but adapter 'cassandra@1.0.0'
reports UNSUPPORTED (008: multi-table atomicity unavailable on Cassandra).
```

Always names provider + missing capability + reason. Same adapter in metadata-only
mode validates fine — rejection is requirement-specific, never blanket.

## Cassandra-requires-revision (008 fail-fast, tested twice)

- Unit: stub matrix mirror in `StartupValidatorTest`.
- Live: real Cassandra matrix through `validate()` in `CassandraSynvaultStoreTest`.

## Mixing is supported (pinned by test)

`mixedProviderCombinationSucceeds`: Cassandra vault + in-memory quest validates
in metadata-only mode. Outcome 3 by design; each port validates independently —
no uniformity rule is enforced.
