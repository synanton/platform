# YDB-POC-011 — CQL-in-ingestion-cache transitional exception

**Status:** Open (transitional; removal owned by YDB-POC-040)
**Date:** 2026-09-26
**Rule:** proposal §8.2 — "No CQL outside Cassandra adapters."

## Exception

`java/ingestion-cache` contains CQL (schema installer, `IngestionCacheClient`
statements) and is imported directly by production call sites:

- `synvault` → `ManifestController`, `SynvaultApplication` (manifest reads)
- `synquest` → `LuceneIndexBuilder` (chunk/embedding reads for indexing)

This predates the ports and is **not** a new violation, but it is a real divergence
from §8.2 as of 011-close. The port surface itself (`synvault-api`, `synquest-api`,
in-memory impls) is Cassandra-free; the divergence lives at the call-site level.

## Why transitional, not target

`ingestion-cache` is not Content Cache 1.26 and not a sanctioned second exception —
it is the legacy path being strangled. No new production code may import
`ingestion-cache` after 011 (enforced in review; ArchUnit rule in 012 covers the
port boundary, this file covers the call sites).

## Removal (YDB-POC-040)

- **Ticket:** YDB-POC-040 — rewire manifest/index call sites off `ingestion-cache`
  onto the ports (`synvault-cassandra` adapter as the bridge), then retire the
  exception. Owner TBD. Target: complete by 021/024 close.
- Until 040 closes, `ingestion-cache` changes are bugfix-only; any new CQL there
  requires an Architecture sign-off referencing this file.
