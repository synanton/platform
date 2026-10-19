# Cluster as-built — platform slice additions

The authoritative inventory lives in gpu-runtime
(`deployments/homelab/claster-as-build.md`). This file records what the
slice's pre-flight verified and what it must not disturb.

## Verified 2026-10-18
| Check | Result |
|---|---|
| Nodes | node0 control-plane (4c/16G); node1 16c/64G (+GTX 1650); node2 20c/64G (+RTX 4060 Ti); node3 20c/64G (+RTX 5060 Ti) |
| CNI | Calico (`calico-system`) |
| StorageClasses | `local-ssd` (no-provisioner, WaitForFirstConsumer, **Delete** — shared with gpu-runtime), `longhorn` (default), `longhorn-static` |
| Existing PVs | `models-node2`, `models-node3` (local-ssd, gpu-runtime), `postgres-data` (longhorn) |
| Registry | `local-registry:5000` — **HTTPS, self-signed cert**, basic auth; a `registry:2` docker-compose service on node0 (not in k8s). Plain HTTP returns nginx 400; unauthenticated HTTPS returns 401. |
| Schedulability | workers are cordoned when the cluster is parked (gpu-runtime `cluster-stop.sh`); `scripts/start.sh` uncordons, `scripts/stop.sh` never cordons |
| Namespaces | `speech`, `gpu-plane` (parked), plus system namespaces. The slice adds `synanton`. |

## Labels (apply once)
```bash
kubectl label node node1 synanton.io/role=cassandra-seed --overwrite
kubectl label node node2 synanton.io/role=compute --overwrite
kubectl label node node3 synanton.io/role=compute --overwrite
kubectl label node node0 synanton.io/role=control-plane --overwrite
```

## Deliberate non-changes
- The shared `local-ssd` StorageClass keeps its `Delete` default — the
  slice's PVs set `Retain` at PV level.
- Longhorn is used only for the synflux content cache (RWX, shared). Cassandra
  stays on local-ssd: RF=3 is already the replication layer.
- No GPU RuntimeClass, no taints: the slice is CPU-only; the GPUs serve
  gpu-runtime (SCEP-7 / Design 1.30).
- Mirror-first applies on both sides: worker nodes have no assumed egress,
  and Docker Hub pulls hang on the M70q network — `mirror.gcr.io` works.
