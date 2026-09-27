# synvault-cassandra

Cassandra `SynvaultStore` adapter — the only module besides `ingestion-cache`
allowed to touch Cassandra concepts. Honest subset per 008: document/chunk/
provenance reads + metadata writes work; revision/delete report `UNSUPPORTED`
(no silent weakening). Title/metadata round-trip via the `ingest_usage`
envelope (legacy-column reuse; YDB schema has first-class columns in 021).
Metrics default-on (Option A symmetry). Tests need Docker ≥26
(`cassandra:4.1`, one shared container). Phase-0 PoC scope.
