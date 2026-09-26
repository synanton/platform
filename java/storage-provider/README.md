# storage-provider

Config-driven provider selection + startup validation (`ProviderSelection`,
`DeploymentRequirements`, `ProviderRegistry`). Rejects incompatible providers
with provider+capability+reason errors (incl. the 008 Cassandra-revision
fail-fast); emits the typed `ActiveProviders` snapshot for observability.
Mixing providers across ports is supported by design. Phase-0 PoC scope.
