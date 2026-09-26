# synvault-api

Persistence port used by Knowledge 1.25 (`SynvaultStore` + `DocumentRevision`
atomicity unit + DTOs). Depends only on `storage-contract`; no provider,
framework, or cross-API imports (enforced by `ApiBoundaryTest` until the
ArchUnit rule lands). Phase-0 PoC scope: `PublicationIntent` is provisional
to 1.27 (see `011-provisional-followup.md`) — do not treat as frozen.
