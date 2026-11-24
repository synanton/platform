# P0-3 — Baseline test snapshot

Status: draft for #859. Snapshot run against commit `f91b59d2c4be7e81b15983557cbc19f1859e36aa` (main after #861), 2026-11-24.

## Reproduction

```bash
./gradlew test --continue --console=plain
```

`--continue` is required to run every module after a failing module. Results were parsed from `java/**/build/test-results/test/TEST-*.xml`.

## Environment limits

- YDB endpoint/fixture was not available (`YDB test fixture failed to start` / `Discovery failed`).
- `REPLICA_PARITY_PRIMARY_URL` and `REPLICA_PARITY_REPLICA_URL` were not set.
- Live vLLM, live extraction gateway, Milvus/Qdrant live-contract containers, and benchmark environments were not enabled.
- No assertion failures were observed; all 36 failures are environment/dependency failures in this run.

## Summary

| Metric | Count |
|---|---:|
| Modules with test results | 48 |
| Test classes | 371 |
| Tests | 1,712 |
| Passed | 1,627 |
| Failed | 36 |
| Errors | 0 |
| Skipped | 49 |

## Per-module snapshot

| Module | Classes | Tests | Passed | Failed | Skipped |
|---|---:|---:|---:|---:|---:|
| annotations | 5 | 26 | 26 | 0 | 0 |
| content-cache-api | 3 | 22 | 22 | 0 | 0 |
| content-cache-grpc | 1 | 7 | 7 | 0 | 0 |
| content-cache-postgres | 5 | 33 | 32 | 1 | 0 |
| control-plane | 1 | 1 | 1 | 0 | 0 |
| eventing-contract | 7 | 31 | 31 | 0 | 0 |
| eventing-outbox | 4 | 20 | 20 | 0 | 0 |
| eventing-publisher | 1 | 4 | 4 | 0 | 0 |
| extraction-client | 3 | 6 | 6 | 0 | 0 |
| extraction-contract | 4 | 43 | 43 | 0 | 0 |
| extraction-engine | 11 | 81 | 80 | 0 | 1 |
| gateway | 8 | 35 | 35 | 0 | 0 |
| gpu-client | 3 | 29 | 29 | 0 | 0 |
| gpu-gateway | 3 | 23 | 23 | 0 | 0 |
| ingestion-cache | 13 | 67 | 67 | 0 | 0 |
| planner | 5 | 30 | 30 | 0 | 0 |
| relix | 45 | 275 | 273 | 0 | 2 |
| security | 9 | 22 | 22 | 0 | 0 |
| shared/common | 11 | 82 | 82 | 0 | 0 |
| storage-contract | 5 | 20 | 20 | 0 | 0 |
| storage-provider | 3 | 20 | 20 | 0 | 0 |
| synanton-bench-baseline | 2 | 3 | 2 | 0 | 1 |
| synanton-bench-convergence | 2 | 12 | 12 | 0 | 0 |
| synanton-bench-corpus | 10 | 28 | 28 | 0 | 0 |
| synanton-bench-emitter | 17 | 26 | 18 | 2 | 6 |
| synanton-llm-client | 4 | 26 | 26 | 0 | 0 |
| synanton-mcp | 6 | 25 | 25 | 0 | 0 |
| synapt | 6 | 20 | 20 | 0 | 0 |
| synflux | 44 | 223 | 222 | 0 | 1 |
| synflux-router | 2 | 4 | 4 | 0 | 0 |
| synquest | 44 | 194 | 191 | 0 | 3 |
| synquest-api | 4 | 18 | 18 | 0 | 0 |
| synquest-cassandra | 3 | 17 | 16 | 0 | 1 |
| synquest-inmemory | 2 | 15 | 14 | 0 | 1 |
| synquest-lucene | 4 | 6 | 5 | 0 | 1 |
| synquest-milvus | 3 | 5 | 0 | 0 | 5 |
| synquest-postgres | 10 | 41 | 40 | 0 | 1 |
| synquest-qdrant | 3 | 5 | 0 | 0 | 5 |
| synquest-ydb | 12 | 47 | 6 | 26 | 15 |
| syntology | 7 | 23 | 23 | 0 | 0 |
| synvault | 4 | 12 | 12 | 0 | 0 |
| synvault-api | 1 | 1 | 1 | 0 | 0 |
| synvault-cassandra | 2 | 13 | 13 | 0 | 0 |
| synvault-inmemory | 3 | 12 | 12 | 0 | 0 |
| synvault-migrate | 1 | 1 | 0 | 1 | 0 |
| synvault-postgres | 8 | 30 | 30 | 0 | 0 |
| synvault-ydb | 10 | 12 | 0 | 6 | 6 |
| topology | 7 | 16 | 16 | 0 | 0 |

## Currently failing tests

These are **baseline failures for this environment**, not new regressions. They are not counted as regressions unless a later phase changes the cause.

### `synquest-ydb` — 26 failures

All: `java.lang.IllegalStateException: YDB test fixture failed to start` (underlying gRPC `ConnectException`).

| Test class | Failing tests |
|---|---|
| `YdbSynquestEngineTest` | `lexicalRetrievalFindsMatchingChunk`, `outOfOrderProjectionCannotRegress`, `noMixedGenerationsDuringPromotion`, `tenantEligibilityIsPreRanking`, `metadataFiltersApply`, `temporalExtensionRejectedWhenUnsupported`, `hybridRetrievalCombinesLexicalAndVector`, `vectorRetrievalRanksByEmbedding`, `generationScopedDelete`, `serviceContextBroadeningIsRejected`, `generationPromotionHidesPreviousGeneration` |
| `YdbVectorIndexUsageTest` | `initializationError` |
| `AdapterMetricsWiringTest` | `searchOperationsAreRecorded` |
| `YdbVectorRetrieverTest` | `shouldReportVectorOnlyCapabilities`, `shouldReturnSameHitsAsEngineVectorMode` |
| `YdbLoadTest` | `leakageAndReplayUnderLoad` |
| `BulkUpsertEquivalenceTest` | `perRowLoadProducesReferenceStateForEquivalence`, `mixedBatchWritesExactlyFreshSubset`, `batchReadReturnsAllKeysInOneQuery`, `dropsIdenticalRetryAsNoop`, `batchReadEmptyOnFreshTable`, `batchCommitSingleTransactionMatchesPerRow` |
| `FilterPaginationTest` | `metadataFilteredLexicalPagesPastFilteredRows` |
| `YdbSynquestGatingTest` | `claimedCapabilitiesHaveSupportedEvidence`, `unsupportedEntriesCarryReasons`, `supportedEntriesNameLoadableEvidence` |

### `synvault-ydb` — 6 failures

All: YDB discovery/fixture failure.

| Test class | Failing tests |
|---|---|
| `YdbConnectivityTest` | `transportAndSessionWork` (`Discovery failed`) |
| `YdbSynvaultGatingTest` | `initializationError` |
| `YdbWriteResilienceTest` | `initializationError` |
| `YdbSynvaultStoreTest` | `initializationError` |
| `YdbProjectionRelayTest` | `initializationError` |
| `YdbRevisionSemanticsTest` | `initializationError` |

### Other environment failures — 4

| Module / class | Test | Cause |
|---|---|---|
| `content-cache-postgres` / `ReplicaReadParityTest` | `knownTenantIsVisibleAndUnknownTenantIsIsolatedOnReplica` | `REPLICA_PARITY_PRIMARY_URL` / `REPLICA_PARITY_REPLICA_URL` missing |
| `synanton-bench-emitter` / `B5YdbLegRun` | `shouldProduceYdbB5Artifact` | YDB discovery failed |
| `synanton-bench-emitter` / `Wiring024BTest` | `wire024BEndToEnd` | YDB discovery failed |
| `synvault-migrate` / `MigrationRoundTripTest` | `initializationError` | YDB discovery failed |

## Skipped / disabled tests

No XML skip message was emitted for most entries; reasons below come from source annotations/assumptions. Counts: 49.

| Module / class | Count | Reason / test names |
|---|---:|---|
| `extraction-engine` / `LlmExtractionLiveTest` | 1 | Requires `LLM_EXTRACTION_URL` (`extractsAgainstTheLiveVllmEndpoint`) |
| `synflux` / `ExtractionStageRealGatewayTest` | 1 | Manual `@Disabled`; needs Docker CLI + sibling content_extractor |
| `relix` / `InMemoryGraphContractTest` | 2 | Capability assumptions: `conditionalWriteRejectsStaleToken`, `atomicMultiNodeWriteRollsBackOnFailure` |
| `synquest-cassandra`, `synquest-inmemory`, `synquest-postgres`, `synquest-ydb` / `SynquestEngineContract` | 4 | `@Disabled("025b: principal/policy eligibility unenforced")` |
| `synquest-milvus` | 5 | `BENCH_LIVE_CONTRACT=1` not set: 2 benchmark legs, 3 vector retriever tests |
| `synquest-qdrant` | 5 | `BENCH_LIVE_CONTRACT=1` not set: 2 benchmark legs, 3 vector retriever tests |
| `synquest-lucene` / `LuceneF2HybridLegRun` | 1 | Live benchmark leg not enabled |
| `synquest` / `BaselineDocumentedRun`, `BaselineBench`, `ReEmbedDemoFixedTest` | 3 | System-property-gated benchmark/demo runs |
| `synanton-bench-baseline` / `BaselineLoadTest` | 1 | Manual 1 GiB baseline load |
| `synanton-bench-emitter` | 6 | `bench.run.*` system properties / live PG/YD B legs not enabled |
| `synquest-ydb` / probes | 14 | `ydb.probe`/`ydb.bulkprobe` gates: `BulkUpsertProbe` (2), `YdbHybridDiag` (3), `YdbDdlProbe` (9) |
| `synvault-ydb` / probes+bench | 6 | `ydb.probe`/`ydb.bench` gates and unreachable viewer: `YdbGate0Probe` (2), `YdbIndexProbe` (1), `YdbQuotaGuardTest` (2), `YdbWriteBench` (1) |

## Known CI flakes

From #836, with rerun evidence, not failures for Gate B:

| Flake | Evidence | Classification |
|---|---|---|
| Real PDF search 503 | #833; rerun passed without a head change | CI readiness flake; re-run at each phase close |
| submit-gradle dependency resolution | #833; rerun passed without a head change | CI dependency-resolution flake; re-run at each phase close |

No additional flake audit was performed beyond #836 for this snapshot. A single local run cannot classify flakiness; these are recorded from CI history. Flaky tests are **not** counted as failures, but must be re-run at each phase close.

## Phase-close rule

At each phase close, re-run this command and compare against this table:

- Previously passing tests that now fail are regressions unless explicitly reclassified.
- Previously failing environment tests remain baseline failures only if the cause is unchanged.
- Known flakes must be re-run; repeated failure becomes a real failure, not a flake.
- Newly passing tests are recorded as improvements.
