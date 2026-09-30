# Benchmark Runner & Evaluation Layer

**File:** `proposal-benchmark-runner.md`
**Status:** Draft for Review
**Owner:** TBD
**Reviewers:** EventLab, UI, platform teams

## 1. Summary

The Benchmark Runner is the missing component connecting EventLab workloads to Synanton UI observations. It consumes a Benchmark Manifest, executes one or more Synanton configurations, collects measurements, verifies reproducibility, and produces a Result Manifest.

## 2. Why This Component Exists

text

```
EventLab → workload
UI       → observation
Missing  → measurement, comparison, reproducible reports
```



Without the Benchmark Runner, EventLab and UI do not close the research loop.

## 3. Placement

Initially placed in `eventlab-benchmarks` or `platform-benchmark`. It must not depend on the UI. It may depend on EventLab core and Synanton client APIs.

## 4. Inputs & Outputs

**Input:** Benchmark Manifest.

**Outputs:**

- Result Manifest,
- `RunCompletedEvent` for UI/analytics,
- artifacts (logs, traces).

## 5. Execution Flow

1. Read Benchmark Manifest.
2. Validate schema and compatibility.
3. Verify or generate corpus.
4. Hash generated corpus; compare to manifest.
5. Run Synanton configuration A and B.
6. Collect timings, recall, index size, RAG quality.
7. Write Result Manifest.
8. Emit completion event.

## 6. ResultSink Abstraction

Avoid hard coupling to Kafka, ClickHouse, MinIO.

java

```
public interface ResultSink {
    void write(ResultManifest manifest);
    void emit(RunCompletedEvent event);
}
```



Implementations: `S3ResultSink`, `KafkaResultSink`, `FileResultSink`, `ClickHouseResultSink`.

## 7. Reproducibility Verification

The runner must:

- hash the generated event stream or corpus,
- compare against the Benchmark Manifest hash,
- set `reproducibility_verified: true|false`,
- mark the run `failed` if hashes differ.

## 8. Cost Controls

- `--dry-run` validates manifest without execution.
- cost estimate before launch.
- approval gate for large runs.
- hard timeout.
- maximum event count guard.

## 9. Metrics

- ingestion throughput,
- total index size,
- search latency p50/p95/p99,
- recall for BM25 / Vector / Hybrid,
- RAG answer quality,
- resource usage.

## 10. Storage & Retention

Proposed layout:

text

```
s3://synvault/manifests/benchmark/<manifest_id>.json
s3://synvault/manifests/result/<result_id>.json
s3://synvault/runs/<result_id>/...
```



Retention policy to be defined. Manifests should be write-once.

## 11. API for UI

http

```
GET /api/v1/benchmarks/results?tenant_id=...&limit=...&cursor=...
GET /api/v1/benchmarks/results/{result_id}
GET /api/v1/benchmarks/manifests/{manifest_id}
```



## 12. Open Questions

1. Where exactly does the runner live?
2. Which metrics are mandatory for MVP?
3. How is cost estimated?
4. Who approves large runs?
5. How are artifacts retained?

## 13. Review Checklist

- □  

  ResultSink interface defined.

- □  

  Reproducibility verification implemented.

- □  

  Cost controls defined.

- □  

  Manifest API defined.

- □  

  Storage layout and retention agreed.

- □  

  Runner added to taxonomy.