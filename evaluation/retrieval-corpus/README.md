# Frozen retrieval corpus — Run C (`demo-fixed`)

The last reproducible retrieval corpus: 82 chunks in the `demo-fixed` tenant of
`docker-cassandra-1` (`ingestion_cache.chunks_payload`) plus the 25-query gold
`queries-q25.jsonl` (copy of `queries-v2.demo-fixed-q25.jsonl`).

`chunks.jsonl` stores `content_ref_id` / `chunk_ordinal` / `chunk_sha256`.
The persisted `chunk_sha256` is the synflux content hash, so the fingerprint
covers chunk contents without copying text. `corpus.json` pins the tenant,
counts, embedding model/dim, and the aggregate fingerprint; `MANIFEST.sha256`
covers all three files.

Verify before any reportable run:

```bash
PYTHONPATH=tools/retrieval-eval python3 -m retrieval_eval.cli check-corpus
# or gate an evaluation:
PYTHONPATH=tools/retrieval-eval python3 -m retrieval_eval.cli evaluate \
  --corpus-manifest evaluation/retrieval-corpus/MANIFEST.sha256 ...
```

Do **not** regenerate the tenant to "refresh" this corpus: re-ingestion can
produce different chunk IDs/hashes (the original 81-chunk `rb-fixed-g5` tenant
is already gone). A new corpus needs a new frozen directory + manifest.
