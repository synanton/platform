# Synanton Developer Guide — Infrastructure Discipline Rules

**Status:** Seeded 2026-10-01 · Parameterization + composition added 2026-10-09 (DOC-D2.3) · **Owner:** Docs owner (TBD — Week 1 gate)
**Scope:** Cross-cutting implementation disciplines citable by PR reviewers.
Pattern additions via DOC-D3.3.

Related: [Benchmark Runner + Vector Engine plan](./implementation/benchmark-runner-vector/INDEX.md)

---

## Cross-runtime boundaries are services, not JNI

Any non-JVM component (Rust, Python, Go, C++) integrated into the platform runs as an
external service communicating over a process or network boundary. Native-library embedding
via JNI, JNA, JNR-FFI, or Panama FFI is out of scope.

Why:

- JNI couples the build toolchain: every JVM build requires the native toolchain for every
  supported platform.
- Native code faults crash the JVM — no isolation, no graceful degradation.
- Cross-language memory management is a class of bugs without a boundary.
- The port architecture already provides a clean seam: `LexicalRetriever`,
  `VectorRetriever`, and `SynvaultStore` are transport-agnostic. An external service is just
  another adapter.

What to do instead:

- Expose the external component over HTTP, gRPC, or Unix socket.
- Implement the relevant port (`LexicalRetriever`, `VectorRetriever`) as a Java client.
- Document the process model (sidecar, sidecar-with-cluster, distributed service) in the
  deployment posture.

What to do if JNI seems necessary:

- Measure first. If the out-of-process option meets the latency target, use it.
- If it doesn't, escalate to an architecture review with the measurement. The decision to use
  JNI requires explicit approval, not a code-review side effect.

Origin: Tantivy integration decision, 2026-10-01. Tantivy and Quickwit integrate as services;
JNI was evaluated and rejected.

---

## System-only fields use the `_internal_` infix

Fields that must never be client-settable are named `_internal_*`
(e.g. `_internal_model_id`, following Architecture 1.32's
`reranker_internal_model_id`). The underscore family is the industry-wide
system-field marker (Solr `_version_`/`_root_`, Elasticsearch
`_id`/`_index`/`_source`/`_routing`/`_meta`), and the `_internal_` infix —
not a bare `_` prefix — is what keeps our names out of the engines'
reserved subspaces:

| Engine | Reserved subspace | Our collision posture |
|---|---|---|
| Solr | Wrapped `_..._` names reserved (`_version_`, `_root_` — verified); `{!...}` local-params + `$param` dereferencing are query syntax (verified) | `_internal_*` is leading-only, outside the reserved wrapped class; `$` never used |
| Elasticsearch | `_`-prefixed metadata (`_id`, `_index`, `_source`, `_routing`, `_meta`, … — verified list) | No listed name starts `_internal_` |
| PostgreSQL | System columns (`tableoid`, `xmin`/`xmax`, `ctid`) carry no underscore prefix | No overlap possible |
| Lucene | No reserved field namespace (internals live in segments) | No overlap possible |
| Milvus | `$meta` dynamic field + `$namespace_id` + `__virtual_pk__` reserved (verified: source + docs); system fields by ID | Reinforces never-`$`, now confirmed rather than reported |
| Qdrant / Cassandra | No known `_`-space reservations | No overlap known |

What not to use:

- `$` / `@` — never. `$` is Solr query syntax and Milvus's reported dynamic-field
  marker; `@` breaks in Painless/scripts and several JSON-path dialects.
- Per-platform encoding of field names — never. Every reader and writer
  (including external adapters and the benchmark harness) would have to share
  the codec, for a round-trip problem none of our engines have.

Scope and grandfather clause:

- Applies to new fields from 2026-10-09. Existing public API fields are NOT
  renamed by this rule — renames are breaking changes under Architecture 1.0
  invariant #29 (explicit versioning required).
- Existing expert knobs (`top_k_dense`, `top_k_lexical`, `rrf_k`,
  `execution_mode`) stay as-is and are documented as the controlled expert
  surface per Architecture 1.32 (profiles for clients, internals withheld
  unless explicitly exposed). Naming documents; withholding enforces.

Origin: field-marking review 2026-10-09. Prior art: unity-hli-v4 `ScopePrefix`
(concept only — no code reuse, no dependency; see thread record).

---

## Parameterized statements, AST-first diagnostics

Batch statements use bound parameters — never inlined literals proportional
to data size. YDB-041 lost days to storage, stats, and index hypotheses
before the statement shape was examined; the param-based batch path then
delivered ~770 rows/s (250× over inlined literals).

- If a batch statement is slow, count the AST nodes first. Literals
  proportional to data size mean: parameterize before investigating anything else.
- Measure from clean-slate schema state: drop-then-create before benchmarking.
  Persisted volumes carry stale tables and `ensureSchema` skips index DDL when
  tables exist — a stale-schema confound reads exactly like a slow engine.

Origin: YDB-041 bulk-upsert investigation, closed 2026-09-29.

---

## Composition boundaries

A composition pairs one metadata stack with one vector engine under separate
namespaces (`metadata.*`, `vector.*`); providers are resolved by role, never
by position:

- `metadata.provider` requires document storage; `vector.provider` requires
  vector search. `validateComposition` fails loud on unknown names or
  capability mismatch — no silent fallback, ever.
- Endpoints are by-name references (`endpoint_ref`), never inline secrets.
- Single-provider deployments stay zero-config: unset `vector.provider`
  defaults to `metadata.provider` at config load, before validation.
  (In the schema file the field is required — defaulting happens at load.)

Reviewers: any PR introducing a cross-provider code path must cite its
composition and show the validation passing. See the composition guide for
the six reference compositions.

Origin: VEC-B2.1/B2.2 provider registry + `validateComposition`, 2026-10-06.
