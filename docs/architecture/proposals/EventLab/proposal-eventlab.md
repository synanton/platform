# EventLab — Deterministic Synthetic Workload & Corpus Generator

**File:** `proposal-eventlab.md`
**Status:** Draft for Review
**Owner:** TBD
**Reviewers:** Synanton architecture group
**Primary consumer:** Lucentrix / Synanton Platform
**Primary language:** Java 21

## 1. Summary

EventLab is a deterministic synthetic event and corpus generator for Synanton experiments. It produces reproducible, high-volume event streams that model repository changes (`CREATE`, `MERGE`, `DELETE`) and configurable metadata distributions.

It is not a general-purpose data faker. It is controlled workload generation infrastructure for benchmarking ingestion, indexing, retrieval, and storage backends.

## 2. Goals

- Deterministic output: same seed + configuration + generator version + schema version → identical bytes.
- Streaming, bounded-memory generation for 10M, 100M, and eventually 1B events.
- Configurable distributions for metadata and operations.
- Configurable temporal workload: constant, sine, spike, periodic bursts, composite.
- Partition-independent generation for parallel and split execution.
- First-class Benchmark Manifest output.
- Java-first implementation, with optional Python only if justified.

## 3. Non-Goals

- General-purpose fake data.
- Realistic NLP text generation.
- Distributed database or message broker.
- Production event generator.
- Replacement for Lucentrix.
- Benchmark framework for every possible system.

## 4. Architectural Position

EventLab belongs to the **Experimentation Layer**:

text

```
Knowledge → Execution → Infrastructure → Experimentation → Interaction
                                                │
                                            EventLab
                                                │
                                                ▼
                                        Benchmark Runner
                                                │
                                                ▼
                                          Synanton UI
```



EventLab supplies controlled experimental input. The Benchmark Runner measures it. Synanton UI observes and compares results.

## 5. Determinism Contract

| ID   | Requirement                                                  |
| ---- | ------------------------------------------------------------ |
| R1   | Same seed + configuration + generator version + schema version → identical output. |
| R2   | Generation can resume with `--start-event` without changing subsequent events. |
| R3   | Changing worker count does not change generated data.        |
| R4   | Partition independence: generating `0..N` in one process equals concatenated partitioned outputs. |
| R5   | Changing the generator algorithm creates a new generator version; old corpora remain reproducible. |
| R6   | The Benchmark Runner verifies the generated corpus hash against the Benchmark Manifest. |

## 6. Event Model

text

```
Event
 ├── sequence
 ├── timestamp
 ├── operation
 ├── documentId
 ├── metadata
 └── payload
```



Operations: `CREATE`, `MERGE`, `DELETE`.

`MERGE` and `DELETE` must reference existing deterministic documents. The lifecycle model must be resolved before Phase 2.

## 7. Lifecycle & State Strategy — Open Design Spike

The hardest unresolved design issue is combining:

- valid `CREATE` / `MERGE` / `DELETE` lifecycles,
- bounded memory at 1B events,
- event-range partition independence,
- byte-for-byte reproducibility.

Two candidate strategies:

1. **Event-first generation** — operation and document ID derived from event sequence. Requires a deterministic validity oracle or relaxed lifecycle correctness.
2. **Document-first generation** — each document lifecycle generated from document ID, then events interleaved or sorted. Guarantees validity but complicates simple event-range partitioning.

**Recommendation:** Run a Phase 0 design spike before Phase 1 implementation. Decide which property is mandatory for MVP and document the trade-off.

## 8. Canonical Serialization

Byte-for-byte reproducibility requires:

- fixed field ordering,
- UTF-8 without BOM,
- LF line endings,
- fixed timestamp format (`2026-01-17T14:32:12.000Z`),
- normalized numbers, preferably integer-only where possible,
- deterministic escaping,
- no insignificant whitespace.

JSONL is the MVP interchange format. A canonical binary format should be considered as the source of truth for large-scale runs.

## 9. Output Formats

- **MVP:** JSON Lines.
- **Later:** canonical binary, Avro, Parquet, custom binary.
- Compression, if used, must checksum the uncompressed canonical bytes.

## 10. API

java

```
public interface EventSource {
    EventCursor open(GeneratorConfig config);
}

public interface EventCursor extends AutoCloseable {
    boolean hasNext();
    GeneratedEvent next();
}
```



Optional `Spliterator<GeneratedEvent>` for parallel generation. Parallelism must not depend on thread scheduling.

## 11. Distribution & Temporal Workload

Supported distributions:

- MVP: uniform, categorical/weighted, sequential, constant.
- Later: normal, exponential, Poisson, log-normal, Zipf, custom.

Temporal workload:

- constant,
- sine,
- spike,
- periodic bursts,
- ramp,
- composite.

The workload configuration must be versioned and hashed into the Benchmark Manifest.

## 12. Benchmark Manifest Integration

EventLab produces a Benchmark Manifest containing:

- generator version,
- algorithm version,
- PRNG,
- seed,
- canonical serialization version,
- workload configuration hash,
- event count,
- document profile,
- tenant ID,
- creation timestamp,
- integrity hash.

See `proposal-manifests.md`.

## 13. MVP Phases

- **Phase 0 — Design spike:** lifecycle/state strategy, canonical serialization, PRNG specification, reproducibility contract.
- **Phase 1 — Deterministic core:** Java 21, streaming API, CLI, JSONL, fixed metadata schema, golden reproducibility tests.
- **Phase 2 — Lifecycle + distributions:** valid `CREATE`/`MERGE`/`DELETE` if Phase 0 resolves it; categorical/uniform/weighted distributions.
- **Phase 3 — Temporal workload:** constant, sine, spike, composite.
- **Phase 4 — Lucentrix integration:** SourcePlugin, `ChangePage`, direct Synanton ingestion.
- **Phase 5 — Scale validation:** 10M, 100M, 1B with throughput, memory, CPU, output size, ingestion throughput, reproducibility verification.
- **Phase 6 — Structured documents:** hierarchical sections, tables, lists, relationships.

## 14. Success Criteria

- Two independent executions produce identical SHA256 output.
- Resume: first 5M + next 5M equals complete 10M stream.
- Parallel: 1-worker output equals 8-worker output.
- 10M and 100M generated without architectural changes.
- Stream consumable by Lucentrix without core changes.

## 15. Open Questions

1. Which lifecycle strategy is chosen for MVP?
2. Is JSONL sufficient for 100M+ runs, or is canonical binary required earlier?
3. How are floating-point distributions made cross-platform deterministic?
4. What is the exact PRNG specification?
5. How is `--start-event` reconciled with document lifecycle state?

## 16. Review Checklist

- □  

  Lifecycle/state strategy decided.

- □  

  Canonical serialization spec written.

- □  

  PRNG specification written.

- □  

  Benchmark Manifest schema frozen for v0.1.

- □  

  Reproducibility tests defined.

- □  

  Lucentrix SourcePlugin contract confirmed.