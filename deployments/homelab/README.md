# Platform homelab deployment

Deploys the #248–#255 vertical slice on the 4-node homelab cluster
(`cluster-as-built.md`; shared with gpu-runtime — the slice lives in its own
namespace and never cordons nodes or touches other namespaces).

## Status

**Vertical Slice — Demo Complete.** End-to-end ingestion (upload → canonical
Cassandra store), asynchronous search projection (outbox consumer → Lucene), and
UI search with BM25/lexical retrieval and full provenance (page/section mapping)
all work. Hybrid semantic search (the Dense leg) requires the GPU plane (TEI) and
is pending integration for live uploads. See
[docs/planning/vertical-slice-execution.md](../../docs/planning/vertical-slice-execution.md)
for the roadmap to "Operationally Complete".

## Running the demo (K8s)

1. **Start the cluster:** `./scripts/start.sh` (uncordons workers, scales the slice
   back up, including the demo services).
2. **Deploy the data layer + demo overlay:** `./scripts/deploy-demo.sh` (data layer
   + secrets, including `security-htpasswd`, then
   `kubectl apply -k k8s/local-real-demo/`). **Do not use `deploy.sh`** — that
   deploys the base #253 slice, not the demo.
3. **Access the UI:** `http://<worker-node-ip>:30080` (NodePort; `security` and
   `synquest` stay ClusterIP behind the UI's `/v1/` nginx proxy).
4. **Log in** with `DEMO_USER` / `DEMO_PASSWORD` from your local `config/.env`.

On a fresh deploy, the outbox consumer builds the initial Lucene projection;
search returns results once that finishes (see Known Limitations).

## Known limitations (vertical slice)

- **Indexing lag:** uploading a new document triggers a background projection
  rebuild. Search returns `503` — the UI shows an "Index updating…" banner — for
  roughly 40 s after an upload.
- **Live semantic search:** newly uploaded documents use BM25-only retrieval
  (`Dense —`) until the TEI GPU service is deployed
  (`gpu-runtime/deployments/homelab/scripts/deploy.sh inference`) and
  `SYNFLUX_PIPELINE_EMBEDDING_ENABLED=true`, with `VLLM_EMBED_BASE_URL` pointed at
  `http://tei-embedding.gpu-plane.svc.cluster.local:8000/v1`.
- **Graph UI:** scoped to bounded neighbor expansion (#508), not a full 20k-node
  visualization.

## Preconditions
1. Cluster reachable (`kubectl get nodes`).
2. `config/.env` created from `config/.env.example` (JWT secret, registry
   credentials, postgres/minio passwords, and `DEMO_USER`/`DEMO_PASSWORD`).
3. Images mirrored: `./scripts/mirror-images.sh` (build/pull → tag → push to
   `local-registry:5000`). The registry itself: `./scripts/check-registry.sh`.
4. Node labels applied (once): see `cluster-as-built.md` §Labels — node1
   `synanton.io/role=cassandra-seed`, node2/3 `synanton.io/role=compute`,
   node0 `control-plane`.
5. Node-local directories created on the workers (see the PV comments):
   `/mnt/local-fast/{cassandra-0,cassandra-1,cassandra-2,postgres,minio,synquest}`,
   and `/mnt/WD6000/nfs_share/synanton-slice` on node1 (fixtures export).

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

The demo overlay (`local-real-demo`) adds:

| Component | Notes |
|---|---|
| security | `/v1/auth/tenant` token mint; `Loaded 1 users` from `security-htpasswd` |
| ui | NodePort **30080** — `/search`, `/ontology` |
| platform | CrashLoopBackOff (#500, out of demo path) |

## Stop / remove
```bash
./scripts/stop.sh                # scale to 0, keep PVCs, nodes stay schedulable
CONFIRM=yes ./scripts/remove.sh  # namespace + PVs
```

## Next steps & roadmap
See [docs/planning/vertical-slice-execution.md](../../docs/planning/vertical-slice-execution.md)
for the P0–P3 priorities, the Demo / Architecture / Operationally-Complete status
model, and the roadmap: the Live Dense leg (GPU/TEI), bounded graph expansion
(#508), and EWP-2 Migration 3 (#528).

## Cluster slot in the #253 run sequence
1. M70q run 1 (outbox-only) — done
2. M70q run 2 (Kafka) — done
3. **Cluster run** — this tree: full acceptance + P1 fault injection under
   real restarts and cross-node scheduling.

P1 procedures: `k8s/fault-injection/README.md`. The first execution is expected to
surface deployment-layer issues (PV binding, registry pulls, scheduling) — budget
one working session and record the findings in #253's first-execution notes.
