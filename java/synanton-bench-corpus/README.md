# synanton-bench-corpus (028a)

v1 corpus generator (`ydb-poc-corpus-v1`): 20,000 docs / 160,000 chunks /
120 golden queries, seed 42, byte-identical output. Spec:
`docs/implementation/ydb-poc/028a-format-spec-corpus.md`.

## One-command generation

```bash
./gradlew :java:synanton-bench-corpus:test  # gates first
# then, from the built classes:
java -Xmx2g -cp <runtime-cp> org.synanton.bench.corpus.CorpusEmit <outdir>
```

Output tree (stable — emitters 028b/c/d/e reference these paths):

```text
<outdir>/
  documents.jsonl       # one doc per line (id, tenant_id)
  chunks.jsonl          # one chunk per line (ids, text, metadata, embedding_b64)
  golden-queries.jsonl  # 120 queries (vectors, relevant + eligible ids)
  manifest.json         # version, seed, counts, leg counts, file shas, generator
```

## Streaming constraint

Chunk rows embed-then-write one at a time. Never hold 160k vectors in memory
(the phantom-SKIP lesson: heap OOM surfaced as silent test SKIP). 2g heap is
margin, not a requirement; 1g must succeed (proven by DeterminismTest's
subprocess leg). Every Q3 emitter copies this shape.
