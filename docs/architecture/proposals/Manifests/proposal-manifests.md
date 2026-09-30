# Benchmark & Result Manifest Schemas (v0.1 Draft)

**File:** `proposal-manifests.md`
**Status:** Draft for Review
**Owner:** TBD
**Reviewers:** EventLab, Benchmark Runner, UI

## 1. Summary

These schemas define the contract between EventLab, the Benchmark Runner, and Synanton UI. They must be versioned, immutable, and published at stable URLs.

## 2. Benchmark Manifest Schema (v0.1)

json

```
{
  "$schema": "https://schemas.synanton.org/benchmark-manifest/v0.1.json",
  "schema_version": "0.1.0",
  "manifest_id": "bm_manifest_f8392a11",
  "created_at": "2026-09-29T12:00:00.000Z",
  "created_by": "eventlab/1.4.2",
  "tenant_id": "tenant-7",
  "generator": {
    "name": "EventLab",
    "version": "1.4.2",
    "algorithm_version": "gen-hybrid-v2"
  },
  "reproducibility": {
    "prng": "PCG64",
    "seed": 94829104821,
    "canonical_serialization_version": "bin-v1",
    "corpus_sha256": "sha256:<real-hash>"
  },
  "workload_config": {
    "hash": "sha256:<real-hash>",
    "event_count": 50000,
    "document_profile": "OHR-Bench-PDF",
    "query_distribution": {
      "type": "zipfian",
      "exponent": 1.07,
      "vocabulary_size": 100000
    }
  },
  "integrity": {
    "manifest_sha256": "sha256:<real-hash>"
  }
}
```



## 3. Result Manifest Schema (v0.1)

json

```
{
  "$schema": "https://schemas.synanton.org/result-manifest/v0.1.json",
  "schema_version": "0.1.0",
  "result_id": "res_run_49102_alpha",
  "benchmark_manifest_id": "bm_manifest_f8392a11",
  "created_at": "2026-09-29T12:34:56.000Z",
  "created_by": "benchmark-runner/0.1.0",
  "tenant_id": "tenant-7",
  "status": "completed",
  "started_at": "2026-09-29T12:00:00.000Z",
  "ended_at": "2026-09-29T12:34:56.000Z",
  "duration_seconds": 2096,
  "reproducibility_verified": true,
  "environment": {
    "synanton_commit": "7a1b8c2f",
    "target_plane_config": "GPU-7-vLLM-active",
    "hardware_node": "node-cluster-prod-04"
  },
  "metrics": {
    "ingestion": {
      "throughput_docs_per_sec": 142.8,
      "total_index_size_bytes": 482910482
    },
    "search_latency_ms": {
      "p50": 12.4,
      "p95": 45.1,
      "p99": 112.8
    },
    "retrieval_quality": {
      "bm25_recall": 0.71,
      "vector_recall": 0.84,
      "hybrid_fusion_recall": 0.93
    }
  },
  "artifacts": {
    "trace_log": "s3://synvault/runs/49102/equalix_trace.log"
  },
  "integrity": {
    "manifest_sha256": "sha256:<real-hash>"
  }
}
```



## 4. Versioning & `$schema` URLs

- `$schema` must resolve to a real, versioned JSON Schema document.
- `schema_version` is a distinct field for validation and compatibility.
- Old schema versions remain published.

## 5. Storage Layout

text

```
s3://synvault/manifests/benchmark/<manifest_id>.json
s3://synvault/manifests/result/<result_id>.json
```



## 6. Tenant Isolation

Both manifests include `tenant_id`. The UI must filter by tenant. Backend must enforce tenant scoping.

## 7. Open Questions

1. Are manifests signed or checksummed only?
2. Who owns the schema registry?
3. How are failed runs represented?
4. How long are manifests retained?
5. Is `query_distribution` required for MVP?

## 8. Review Checklist

- □  

  Real `$schema` URLs published.

- □  

  `schema_version`, `created_at`, `created_by`, `tenant_id`, `status` added.

- □  

  Integrity hash defined.

- □  

  Compatibility matrix defined.

- □  

  Storage layout agreed.

------

# Proposal: Synanton Organization Taxonomy & Research Loop

**File:** `proposal-org-taxonomy.md`
**Status:** Draft for Review
**Owner:** Synanton organization maintainers

## 1. Summary

This proposal updates the Synanton organization README to include EventLab and UI as first-class architectural layers and makes the research loop explicit.

## 2. Five-Layer Taxonomy

| Layer           | Purpose                                | Proposed repositories                                        |
| --------------- | -------------------------------------- | ------------------------------------------------------------ |
| Interaction     | Observe, explore, evaluate             | `synanton/ui`                                                |
| Experimentation | Generate controlled workloads, measure | `synanton/eventlab` (+ Benchmark Runner)                     |
| Knowledge       | Build and process enterprise knowledge | `synanton/platform`, `synanton/lucentrix`, `synanton/synquest`, `synanton/relix`, `synanton/syntology` |
| Execution       | Control distributed business execution | `synanton/resolutor`, `synanton/equalix`, `synanton/commitix` |
| Infrastructure  | Processing infrastructure              | `synanton/gpu-runtime`, `synanton/content-extractor`, backing services |

**Note:** Repository names must be reconciled with the current GitHub organization. Earlier documents used `synflux`, `synvault`, `gpu-plane`, `vllm-contracts`. One canonical list is required.

## 3. Research Loop

text

```
architecture
     ↓
implementation
     ↓
controlled workload       ← EventLab
     ↓
measurement               ← Benchmark Runner
     ↓
interactive observation   ← Synanton UI / Evaluation Tool
     ↓
revised architecture
     └──────────────────────→
```



## 4. README Update Draft

markdown

```
# Synanton Enterprise Knowledge Platform

Synanton is an AI-native enterprise knowledge platform combining hybrid search
(BM25 + Vector) and ontological reasoning (GraphRAG).

## Layers

- **Interaction Layer** (`synanton/ui`): role-aware multi-tool UI platform.
- **Experimentation Layer** (`synanton/eventlab`): deterministic workload generation
  and benchmark execution.
- **Knowledge Layer**: distributed knowledge processing and retrieval.
- **Execution Layer**: distributed business execution.
- **Infrastructure Layer**: storage, messaging, analytics, GPU inference.

## Research Loop

architecture → implementation → controlled workload → measurement →
interactive observation → revised architecture
```



## 5. Open Questions

1. What is the canonical repository list per layer?
2. Does the Benchmark Runner live in EventLab or as a separate module?
3. How are Infrastructure repositories separated from backing services?
4. Should Interaction and Experimentation be listed before Knowledge in the README?

## 6. Review Checklist

- □  

  Repository names reconciled.

- □  

  Five layers documented.

- □  

  Research loop included.

- □  

  EventLab and UI given explicit roles.

- □  

  Benchmark Runner shown in taxonomy.