# Vector composition configuration guide (DOC-D2.1)

**Schemas:** `schemas/deployment/synquest-composition.schema.json` (`0.1.0-draft`).
**Examples:** `schemas/deployment/examples/composition-*.yaml` (all six).
**Rule engine:** `ProviderRegistry.resolveComposition` + `validateComposition`
(`java/storage-provider`). **Ports:** `VectorRetriever`, `SynquestIndexWriter`
(`java/synquest-api`).

---

## 1. Model

A composition pairs one **metadata stack** with one **vector engine**:

```yaml
synquest:
  metadata:
    provider: cassandra      # storage + lexical stack (VEC-B2.1 namespace)
  vector:
    provider: milvus         # vector engine (VEC-B2.1 namespace)
    endpoint_ref: "${BENCH_MILVUS_URI}"   # by-name ref only, never inline secrets
```

Single-provider deployments stay zero-config: an unset `vector.provider`
defaults to `metadata.provider` (VEC-B2.2). In the schema file the field is
required — the defaulting happens at config load, before validation.

## 2. Validation rules (VEC-B2.2, enforced at startup)

- Unknown names fail loud (`unknown provider for port …` listing available).
- Metadata role requires document storage (`synvault.document` capability).
- Vector role requires vector search (`synquest.vector` capability).
- Every failure names provider + capability + reason. No silent fallback.

## 3. The six reference compositions

| File | Metadata | Vector | Notes |
|---|---|---|---|
| `composition-cassandra-lucene.yaml` | cassandra | lucene | Baseline; in-process JVM |
| `composition-cassandra-milvus.yaml` | cassandra | milvus | External service; needs Milvus stack (§5 bench script) |
| `composition-cassandra-qdrant.yaml` | cassandra | qdrant | Single container (qdrant-bench) |
| `composition-pg-milvus.yaml` | postgres | milvus | Bench PG on :5433 + Milvus stack |
| `composition-pg-qdrant.yaml` | postgres | qdrant | Bench PG on :5433 + Qdrant |
| `composition-ydb-ydb.yaml` | ydb | ydb | Single-provider; `ydb-poc` on :2135 |

Each file is a complete working config: fusion (`rrf`, `rrf_k: 60`) and
execution (`parallel` default; PG+Qdrant ships `sequential` as the worked
example of per-composition mode) included.

## 4. Fusion and execution

```yaml
  fusion:
    method: rrf            # only method today; enum grows, never renames
    rrf_k: 60
  execution:
    mode: parallel         # or sequential (metadata-first, Lucene path only —
                           # per-backend sequential is Phase-5; see B4 record)
    per_query_override: true
```

Sequential constrains the dense leg to the lexical candidate universe; result
shapes are identical to parallel by construction (shared fusion path).
Crossover selectivity is unmeasured without dense legs (B5.6 record).

## 5. Running a composition (bench stacks)

```bash
scripts/bench-vector-stacks.sh   # contract: external bind mounts only ($DATA_ROOT),
                                 # IP-based endpoints (embedded DNS unreliable here),
                                 # port 18091 for synvault (8091 taken by gpu gateway)
```

Then point `endpoint_ref` vars at the live stacks (see `.env.default`) and
validate any file against the schema before use. Narrow (vector-only) vs full
ports: adapters expose `VectorRetriever`; the `SynquestEngine` facade routes
`mode=VECTOR` through it (B1.4); lexical paths are unchanged.
