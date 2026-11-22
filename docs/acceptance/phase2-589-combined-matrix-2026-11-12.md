# Phase 2a / #589 combined matrix evidence — 2026-11-12

**Status:** local real-store combined matrix passed.
**Tree:** `dd2de84c2160cf459dc561fd7f8731eb13f6d42e` (post-#592 closure; includes #639 and #640).
**Scope:** resolution, promotion, reconciliation, and flag isolation against real Cassandra + Neo4j. This is not a live K8s/HTTP activation artifact.

## Environment

- Cassandra: Testcontainers `cassandra:4.1`
- Neo4j: Testcontainers `neo4j:5.26`, no authentication
- Backends: real Cassandra schema, real Neo4j Bolt; no mocked graph writes for the matrix
- No vLLM/TEI/K8s dependency

## Invocation

```bash
./gradlew :java:relix:test --rerun-tasks \
  --tests 'org.synanton.relix.integration.ConsumerFaultInjectionIntegrationTest' \
  --tests 'org.synanton.relix.integration.ConsumerFlagIsolationTest'
```

Result: `BUILD SUCCESSFUL` (2m47s).

## Scenario results

| Scenario | Expected | Observed |
|---|---|---|
| Promotion lost response after commit | Commit persists before failure; retry uses same identity; no duplicate `:REL`/evidence/`:SUPPORTS` | PASS — first commit queried, retry returned 0 new/1 matched, one of each edge/node |
| Resolution lost response after commit | Retry converges on one active edge | PASS |
| Concurrent promotion of same relation | Fencing/claim prevents duplicate relation, evidence, support | PASS — one `:REL`, one evidence, one `:SUPPORTS`; work COMPLETED |
| Shared canonical endpoint | Two relations keep separate canonical identity/evidence; removing one does not affect the other | PASS |
| Combined resolution → promotion → reconciliation | Invariants preserved; no duplicates | PASS — one of each; active edges remain singular |
| Restart with in-flight PROCESSING work | Recovery without duplicate state | PASS |
| Expired worker vs reclaimed worker | Higher/current fence wins; stale worker rejected | PASS |
| Missing target | Terminal stale, no infinite retry | PASS |
| Transient failure | Pending; reconciliation does not falsely complete | PASS |
| Graph-present/Cassandra-incomplete repair | Cassandra completion from exact graph identity | PASS |
| Disabled resolution consumer | No claims/candidate/graph writes | PASS |
| Disabled promotion consumer | No claims/promotion | PASS |
| Disabled reconciler | No claims/graph access | PASS |

`#639` promotion lost-response remains a regression test in this suite; it is not a separate one-off.

## Qualification / remaining #589 work

This artifact proves the combined local real-store matrix on the post-#592 tree. #589 remains open for the live HTTP/K8s activation rerun and any activation-specific evidence required by the issue; this document must not be reused as evidence for a different commit.

## Related

- ADR-029 source-change lifecycle (design gate for 2b)
- #589 combined activation gate
- #592 reconciliation migration (closed)
- #638 capability composition/lint
- #639 promotion lost-response regression

## Live K8s rerun — 2026-11-12

**Result:** PASS (29.0s) after recovering the cluster data layer.
**Deployed tree:** `8c56ea1d9d0fe064199ad6d77681af9d9f47560a` (images rebuilt and pushed with `IMAGE_TAG=local-real-demo`).
**Cluster:** `kubernetes-admin@kubernetes`, namespace `synanton`.
**UI:** deployed NodePort `http://192.168.10.31:30080`.
**APIs:** port-forwarded Synflux (`18090`) and Relix (`18084`); no mocked graph writes.

### Infrastructure recovery

- Cassandra headless Service now has `publishNotReadyAddresses: true`; the readiness probe timeout is 10s. Without this, cassandra-1/2 could not resolve the seed endpoint before cassandra-0 became Ready.
- Neo4j Service now publishes not-ready addresses so Relix can reach Bolt even while the Neo4j pod readiness probe is still false. Neo4j is otherwise healthy and the graph journey passed.
- `deploy-demo.sh` completed the data-layer and overlay rollout, and the consumer flags were enabled:
  - Synflux: `EXTRACTION_PROVIDER=llm`, `EXTRACTION_CONSUMER_ENABLED=true`, `EXTRACTION_GRAPH_ENABLED=true`, `PROMOTION_PRODUCER_ENABLED=true`
  - Synquest: `RESOLUTION_CONSUMER_ENABLED=true`
  - Relix: `PROMOTION_CONSUMER_ENABLED=true`, `RECONCILIATION_ENABLED=true`

### Live E2E

Command (from `test/e2e`):

```bash
SYNFLUX_URL=http://127.0.0.1:18090 \
VITE_RELIX_URL=http://127.0.0.1:18084 \
BASE_URL=http://192.168.10.31:30080 \
LOCAL_REAL_DEMO_TENANT=demo \
SYNANTON_JWT_SECRET=<secret> \
npm run test:entity-graph-live
```

Observed: `1 passed (29.0s)` — upload → extraction → graph commit → resolution → validation → promotion → Entity Graph with `tested_every` edge and evidence.

Artifact: [`artifacts/k8s-activation-2026-11-12/playwright-live.txt`](artifacts/k8s-activation-2026-11-12/playwright-live.txt).

This is the live HTTP/K8s activation rerun on the `8c56ea1d` tree. Store-level evidence above remains valid; the combined gate now has both local and live evidence.
