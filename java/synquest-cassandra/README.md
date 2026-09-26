# synquest-cassandra (024A)

Cassandra-named retrieval adapter over the current ingestion-cache-backed Lucene
path — there is no Cassandra search implementation to move, so the name carries
the comparison leg while the technology mirrors the production service
(per-tenant Lucene indexes, BM25 defaults, KNN cosine, RRF k=60).
Field schema mirrors `LuceneIndexBuilder` (`text`, `embedding`); any field-schema
change there must be mirrored here until 040 retires the duplication.
Pre-ranking eligibility via tenant-dir separation. Metrics default-on.
Phase-0/1 PoC scope (throwaway until 010 flips).
