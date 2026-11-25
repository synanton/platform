# Platform homelab deployment

Deploys the #248–#255 vertical slice on the 4-node homelab cluster
(`cluster-as-built.md`; shared with gpu-runtime — the slice lives in its own
namespace and never cordons nodes or touches other namespaces).

## Status

**Phase 5 engineering complete; deployed demo gated on #888.** The SPA workflow
(submit → process → results/quarantine → provenance → explicit promote →
document graph) and tenant/security hardening are merged. The deployed
environment must still be updated and verified with `verify-demo.sh`; MCP
(#887) and Search/Chat (#886) are explicitly deferred and are not advertised as
working demo paths.

See [docs/planning/vertical-slice-execution.md](../../docs/planning/vertical-slice-execution.md)
for the roadmap to "Operationally Complete".

## Lifecycle contract

| Action | Command | Behavior |
|---|---|---|
| Fresh deploy | `./scripts/deploy-demo.sh` | Applies data layer, secrets, overlay, and waits for RAKG services. |
| Resume | `./scripts/start.sh` | Preflights required workloads, uncordons only labelled Synanton workers, scales required workloads, waits for readiness. |
| Stop | `./scripts/stop.sh` | Scales managed workloads to zero in reverse order; keeps PVCs/PVs/secrets; leaves nodes schedulable. |
| Full cluster shutdown | `./scripts/cluster-stop.sh --full` | Explicitly delegates cordon/drain/poweroff to gpu-runtime; `--poweroff` also requires it. Plain `cluster-stop.sh` performs a platform-only stop. |
| Verify | `./scripts/verify-demo.sh` | Read-only readiness/image/ingress report; required failures exit non-zero. |
| Remove | `CONFIRM=yes ./scripts/remove.sh` | Deletes namespace and platform PVs; node-local data remains by policy. |

`start.sh` and `stop.sh` fail if a required managed workload is missing; a
partial lifecycle must not look successful. `start.sh` never cordons nodes and
only uncordons nodes labelled `synanton.io/role`. `platform` is optional and is
left stopped unless `START_PLATFORM=1` is set.

## Running the demo (K8s)

1. **Deploy:** `./scripts/deploy-demo.sh` (data layer + secrets + demo overlay).
   **Do not use `deploy.sh`** — that deploys the base #253 slice, not the demo.
2. **Resume later:** `./scripts/start.sh`.
3. **Verify:** `./scripts/verify-demo.sh`; record pod image IDs/digests and any
   deferred warnings in the demo run note.
4. **Access the UI:** `http://<worker-node-ip>:30080`.
5. **Log in** with `DEMO_USER` / `DEMO_PASSWORD` from your local `config/.env`.

On a fresh deploy, the outbox consumer builds the initial Lucene projection;
search returns results once that finishes (see Known Limitations). The workflow
does not depend on Search/Chat. Record the deployed image digests; a running pod
alone is not proof that the Phase 5 UI bundle is deployed.

## Known limitations (vertical slice)

- **Indexing lag:** uploading a new document triggers a background projection
  rebuild. Search returns `503` — the UI shows an "Index updating…" banner — for
  roughly 40 s after an upload.
- **Legacy corpus is lexical-only:** the 58 pre-existing demo manifests were
  ingested before GPU embedding was enabled, so they have no canonical chunk
  vectors and appear as `BM25 #N · Dense —`. Newly uploaded documents get full
  hybrid retrieval (`BM25 · Dense · RRF`) through the in-cluster TEI service;
  backfilling the legacy corpus is deferred. See
  `docs/acceptance/live-dense-2026-11-06.md`.
- **GPU-plane networking is a demo exception:** synquest/synflux reach
  `tei-embedding`, and synflux reaches `vllm-extraction`, directly across
  namespaces via additive NetworkPolicies. This bypasses the gpu-plane
  gateway/Envoy perimeter; route via Envoy before production use (see the
  manifest TODO and #538).
- **RAKG graph services:** the overlay now includes Neo4j, Relix, and Syntology.
  The Compose pipeline is proven locally; K8s activation evidence remains #589.
- **Graph UI:** scoped to bounded neighbor expansion (#508), not a full 20k-node
  visualization.

## Preconditions
1. Cluster reachable (`kubectl get nodes`).
2. `config/.env` created from `config/.env.example` (JWT secret, registry
   credentials, Postgres/Neo4j/MinIO passwords, and `DEMO_USER`/`DEMO_PASSWORD`).
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
| Postgres | node1 | 1 | extraction gateway + Syntology store |
| MinIO | node1 | 1 | object store + extraction handoff |
| synflux | node2/3 | 2 | **required** anti-affinity (P1 tests depend on it); shared content cache (Longhorn RWX) and fixtures (NFS) |
| extraction-gateway | node2 | 1 | CPU |
| synquest | node1 | 1 | Lucene index on local-ssd |
| Kafka | — | 0 | outbox-only is the cluster-run default (optional profile) |

The demo overlay (`local-real-demo`) adds:

| Component | Notes |
|---|---|
| security | `/v1/auth/tenant` token mint; `Loaded 1 users` from `security-htpasswd` |
| ui | NodePort **30080** — `/search`, `/ontology`, Entity Graph |
| neo4j | RAKG graph store; Longhorn PVC; `neo4j-cred` secret |
| relix | graph write/read + Entity Graph API; port **8084** |
| syntology | ontology validation; Postgres DB init and TDB2 PVC |
| platform | CrashLoopBackOff (#500, out of demo path) |
| prometheus | Minimal scrape store for #736; scrapes `synflux:8090/actuator/prometheus`; 10Gi Longhorn history |

Build/push the RAKG images with
`IMAGE_TAG=local-real-demo ./scripts/mirror-images.sh`; the overlay uses matching
tags until the activation runbook pins digests.

## Image provenance and deferred ingress

- Overlay images use the `local-real-demo` tag; `imagePullPolicy: Always` means a
  pushed rebuild is picked up on pod restart. `verify-demo.sh` prints image IDs
  for the deployed pods.
- Working ingress: auth, ontology, entity graph, document graph, synflux, and
  the workflow/search proxied through synquest.
- Topology is explicitly excluded from the current demo ingress because no
  topology target is deployed; Admin falls back to an unavailable message.
- Deferred: MCP (#887, no deployed target) and full Search/Chat contract work
  (#886). Do not advertise these paths as production-ready.
- `verify-demo.sh` requires UI routes `/synflux/`, `/api/v1/documents/`, and
  `/topology/`, plus the Workflow bundle. A stale UI image fails the check.

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
