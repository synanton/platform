# synvault-migrate (035)

PoC-scope Cassandra → YDB migration through the port abstractions (never backend
APIs). Extracts documents/chunks/provenance via the source port, loads as fresh
migration-marked revisions on the target, validates by per-document checksums,
rolls back by deleting migrated documents. This module intentionally depends on
concrete adapters — it is the composition root, not domain code. Source rows
without provenance refuse to migrate (no fabricated lineage). Throwaway scope
until 010 flips.
