# Platform homelab deployment

Deploys the #248–#255 vertical slice on the 4-node homelab cluster
(`cluster-as-built.md`; shared with gpu-runtime — the slice lives in its own
namespace and never cordons nodes or touches other namespaces).

## Preconditions
1. Cluster reachable (`kubectl get nodes`).
2. `config/.env` created from `config/.env.example` (JWT secret, registry
   credentials, postgres/minio passwords).
3. Images mirrored: `./scripts/mirror-images.sh` (build/pull → tag → push to
   `local-registry:5000`). The registry itself: `./scripts/check-registry.sh`.
4. Node labels applied (once): see `cluster-as-built.md` §Labels — node1
   `synanton.io/role=cassandra-seed`, node2/3 `synanton.io/role=compute`,
   node0 `control-plane`.
5. Node-local directories created on the workers (see the PV comments):
   `/mnt/local-fast/{cassandra-0,cassandra-1,cassandra-2,postgres,minio,synquest}`,
   and `/mnt/WD6000/nfs_share/synanton-slice` on node1 (fixtures export).

## Quick start
```bash
./scripts/verify-cluster.sh          # five pre-flight checks
./scripts/mirror-images.sh           # builds/pushes; prints "Set IMAGE_TAG=..."
IMAGE_TAG=slice-YYYYMMDD ./scripts/deploy.sh
./scripts/run-slice.sh               # port-forwards + acceptance script
./scripts/stop.sh                    # scale to 0, keep PVCs
CONFIRM=yes ./scripts/remove.sh      # namespace + PVs
```

## What gets deployed
| Component | Nodes | Replicas | Notes |
|---|---|---|---|
| Cassandra | node1/2/3 | 3 (NTS RF=3) | local-ssd static PVs, Retain; anti-affinity required |
| Postgres | node1 | 1 | extraction gateway store |
| MinIO | node1 | 1 | object store + extraction handoff |
| synflux | node2/3 | 2 | **required** anti-affinity (P1 tests depend on it); shared content cache (Longhorn RWX) and fixtures (NFS) |
| extraction-gateway | node2 | 1 | CPU |
| synquest | node1 | 1 | Lucene index on local-ssd |
| Kafka | — | 0 | outbox-only is the cluster-run default (optional profile) |

## Cluster slot in the #253 run sequence
1. M70q run 1 (outbox-only) — done
2. M70q run 2 (Kafka) — done
3. **Cluster run** — this tree: full acceptance + P1 fault injection under
   real restarts and cross-node scheduling.

P1 procedures: `k8s/fault-injection/README.md`. The first execution is
expected to surface deployment-layer issues (PV binding, registry pulls,
scheduling) — budget one working session and record the findings in #253's
first-execution notes.
