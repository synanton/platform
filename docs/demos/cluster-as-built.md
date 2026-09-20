# Cluster as-built

What's actually running, as observed directly via `kubectl`/`ssh` - as
distinct from `persistent-storage-plan.md`, which is a proposal (some of it
matches reality, some doesn't - noted inline below). See "Cluster hardware"
for the physical inventory this is built on.

## Nodes

| Node    | Role                  | K8s version | CPU | RAM  | GPU               | Internal IP   |
|---------|-----------------------|-------------|-----|------|-------------------|---------------|
| `node0` | control-plane, master | v1.37.0     | 4   | 16Gi | none              | 192.168.10.30 |
| `node1` | worker                | v1.37.0     | 16  | 64Gi | GTX 1650, 4GB     | 192.168.10.31 |
| `node2` | worker                | v1.37.0     | 20  | 64Gi | RTX 4060 Ti, 16GB | 192.168.10.32 |
| `node3` | worker                | v1.37.0     | 20  | 64Gi | RTX 5060 Ti, 16GB | 192.168.10.33 |

All four also have a `192.168.18.x` address on a separate 1 Gbit interface
(the `192.168.10.x` addresses above are the 10 Gbit/s SFP+ mesh - see
"Cluster hardware"). `node0` doubles as the container registry host (see
below) - `/etc/hosts` on client machines maps `local-registry` to
`192.168.10.30`.

## Networking (CNI)

Calico (`calico-system` namespace) - `calico-node` DaemonSet on all 4 nodes,
`calico-apiserver`, `calico-kube-controllers`, `calico-typha`, plus
`goldmane`/`whisker` (Calico's flow-log/observability sidecars). No custom
network policy at the cluster level beyond what individual workloads define
in their own namespaces (e.g. `speech-to-speech-k8s`'s
`k8s/network-policy/`).

## GPU

- NVIDIA GPU Operator's device plugin DaemonSet
  (`nvidia-device-plugin-daemonset`, `kube-system`) runs on the 3 GPU
  workers only (`node1`-`node3`) - `node0` has no GPU and isn't targeted.
- `RuntimeClass nvidia` (handler `nvidia`) is what workload Pods set via
  `runtimeClassName: nvidia` to get GPU access - see any GPU-requesting
  Deployment in `speech-to-speech-k8s/k8s/` for the pattern.
- Each GPU node exposes exactly `nvidia.com/gpu: 1` (one physical GPU, not
  MIG-partitioned or time-sliced) - see that repo's `docs/architecture.md`
  "GPU scheduling constraint" for what this means for scheduling two
  GPU-requesting Deployments onto one node.

## Storage

Longhorn (`longhorn-system` namespace) is installed and running - CSI
attacher/provisioner/resizer/snapshotter, engine images, instance managers,
`longhorn-csi-plugin` DaemonSet. Detailed install steps (prerequisites,
air-gapped image mirroring, troubleshooting) are documented in
`speech-to-speech-k8s/docs/longhorn-setup.md` rather than duplicated here.

Current `StorageClass`es:

```
NAME                 PROVISIONER                    RECLAIMPOLICY   VOLUMEBINDINGMODE      DEFAULT
local-ssd            kubernetes.io/no-provisioner   Delete          WaitForFirstConsumer   -
longhorn             driver.longhorn.io             Delete          Immediate              yes
longhorn-static      driver.longhorn.io             Delete          Immediate              -
```

**Gap vs. `persistent-storage-plan.md`**: that plan's 3-tier design
(`longhorn-replicated` + `local-fast` + `nfs-datasets`, with an
`nfs-subdir-external-provisioner`-backed `StorageClass` for a 16.4TB HDD
array on node1) is only partially real:

- The Longhorn tier exists, but as `longhorn`/`longhorn-static`, not
  `longhorn-replicated`, and its actual per-node disk config hasn't been
  verified against the plan's Node2-specific "744 GiB reserved" instruction
  in this pass.
- `local-ssd` exists (matching the plan's local-fast intent) but under a
  different name than the plan's `local-fast`.
- **No `nfs-datasets` StorageClass exists** - the `nfs-subdir-external-provisioner`
  Helm install was never applied. There IS an NFS export from `node1`
  (`node1:/mnt/WD6000/nfs_share`, confirmed mounted on an admin workstation
  at `/mnt/wd6000`), but it's a plain filesystem export for manual/workstation
  access, not wired into Kubernetes as a `StorageClass` - a pod can't request
  it via a PVC today.

In practice, `speech-to-speech-k8s` (the main workload on this cluster)
doesn't use Longhorn or these StorageClasses for its model caches at all -
it uses per-node `hostPath` mounts directly (`/mnt/local-fast/...`) for
latency/simplicity, keeping Longhorn for whatever needs real replicated
block storage instead.

## Container registry

Plain `registry:2` (Docker's own reference implementation), run as a
`docker compose` service directly on `node0` (**not** inside Kubernetes) -
container name `docker-registry-registry-1`. Reachable at
`local-registry:5000` (via the `/etc/hosts` mapping above), requires basic
auth (`docker login local-registry:5000`). Every workload's
`imagePullSecrets` reference a `local-registry-cred` `Secret` created per-namespace
via `scripts/create-registry-secret.sh` in `speech-to-speech-k8s` -
replicate that pattern for any new namespace that needs to pull from it.

Public images get mirrored into it rather than pulled directly by cluster
nodes (worker nodes have no assumed direct internet egress for image pulls,
though in practice at least `node1` does have general internet access - see
`speech-to-speech-k8s/docs/deployment.md`'s gotcha about a transient
DNS-resolution failure on `node1` during a cold-start model download).
`speech-to-speech-k8s/scripts/mirror-images.sh` is the reference pattern:
`docker pull <public> && docker tag ... local-registry:5000/<name>:<tag> && docker push ...`.

## Namespaces in use

```
calico-system     - CNI
kube-system       - core + NVIDIA device plugin
longhorn-system   - Longhorn
tigera-operator   - manages the Calico install
speech            - s2s-k8s (the only application workload today)
```

