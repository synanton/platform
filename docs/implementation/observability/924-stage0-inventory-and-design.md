# #924 Stage 0 — homelab observability inventory and design

**Issue:** synanton/platform#924
**Status:** Stage 0 complete; no deployment performed
**Evidence date:** 2026-11-26
**Scope:** read-only inventory and Stage 1 design for centralized Kubernetes logs and operator UI

## 1. Executive decision

The homelab can host a small Alloy + Loki + Grafana stack, but **Stage 1 must not be deployed
until two storage/cleanup decisions are made**:

1. **Longhorn is degraded.** Only node2 and node3 have Longhorn disks; node1 has no disk status.
   Existing 3-replica volumes are attached but `degraded`. A new 3-replica Loki volume would
   inherit that state. Use a dedicated 2-replica policy for Loki, or restore the node1 disk and
   repair existing volumes first.
2. **The GPU plane is not in a clean running state after maintenance.** `gpu-plane` deployments
   are scaled to zero and 653 pod objects are `ContainerStatusUnknown`/`Unknown`. Do not size the
   first log stack against the historical GPU workload; clean up and re-establish the workload
   before measuring Stage 1.

With those caveats, the recommended Stage 1 shape is:

- dedicated `observability` namespace;
- Grafana Alloy DaemonSet on all four nodes (tolerating the control-plane taint);
- single-binary Loki with filesystem storage on a dedicated 20Gi Longhorn volume, 2 replicas,
  14-day retention;
- one Grafana deployment with authenticated, TLS-only operator access;
- no Prometheus or Tempo as mandatory Stage 1 dependencies;
- no raw prompts/responses, secrets, or full document text in Loki;
- integration with #889 lifecycle conventions, without duplicating the Deployment Configurator.

## 2. Inventory scope and evidence

Read-only commands used included:

```text
kubectl get nodes -o custom-columns=...
kubectl get ns
kubectl get pods -A --field-selector=status.phase=Running
kubectl get deploy,sts -A
kubectl get pvc,pv -A
kubectl get sc -o yaml
kubectl get nodes.longhorn.io -n longhorn-system
kubectl get ingress -A -o yaml
kubectl get secrets --field-selector type=kubernetes.io/tls -A
kubectl get networkpolicy -A
kubectl get ds -A
kubectl get events -A --sort-by=.lastTimestamp
kubectl logs -n synanton <pod> --all-containers --since=1h | wc -c
```

No manifests, secrets, Helm releases, workloads, or storage were changed.

## 3. Cluster topology and capacity

| Node | Role / capacity | GPU | Scheduling notes |
|---|---|---|---|
| node0 | control-plane, 4 CPU, 16GiB, 110 pods | none | `node-role.kubernetes.io/control-plane:NoSchedule` |
| node1 | worker, 16 CPU, 64GiB, 110 pods | GTX 1650 | label `synanton.io/role=cassandra-seed` |
| node2 | worker, 20 CPU, 64GiB, 110 pods | RTX 4060 Ti | label `synanton.io/role=compute` |
| node3 | worker, 20 CPU, 64GiB, 110 pods | RTX 5060 Ti | label `synanton.io/role=compute` |

- Kubernetes: v1.37.0 on Ubuntu 24.04; containerd 2.2/2.3/2.4.
- CNI: Calico; BGP disabled for the host; eBPF disabled.
- No node reports MemoryPressure, DiskPressure, or PIDPressure.
- **Metrics API is unavailable** (`kubectl top nodes` fails). Actual CPU/memory usage cannot be
  measured from the cluster; only requests and allocatable capacity are known.
- Namespaces: `calico-system`, `default`, `freelingo`, `gpu-plane`, `kube-node-lease`,
  `kube-public`, `kube-system`, `longhorn-system`, `speech`, `synanton`, `synanton-gate1`,
  `tigera-operator`.
- No Pod Security Admission enforce label is set outside the two privileged system namespaces.

### 3.1 Post-maintenance workload state

At evidence time:

- 72 pods were `Running`;
- 652 pods were `ContainerStatusUnknown` and 1 was `Unknown`, all in `gpu-plane`;
- all `gpu-plane` Deployments were scaled to 0 (`envoy`, `gpu-gateway`, `postgres`,
  `tei-embedding`, `tei-embedding-small`, `vllm-extraction`, `vllm-reranker`, `vllm-synthesis`);
- `synanton/platform` and `synanton/prometheus` were scaled to 0;
- `freelingo` and `speech` Deployments were scaled to 0;
- running `synanton` pods included Cassandra (3), Postgres, MinIO, Neo4j, synflux (2),
  extraction-gateway, security, relix, syntology, synquest, and UI; several had recent restarts;
- no `gpu-plane` pod was providing log volume.

This is recorded as an operational risk, not remediated by this document.

## 4. Storage inventory

| StorageClass | Provisioner | Reclaim | Binding | Role |
|---|---|---|---|---|
| `longhorn` (default) | `driver.longhorn.io` | Delete | Immediate | general RWO/RWX volumes |
| `longhorn-static` | `driver.longhorn.io` | Delete | Immediate | static Longhorn |
| `local-ssd` | `kubernetes.io/no-provisioner` | Delete | WaitForFirstConsumer | node-local static PVs |
| `nfs-cold` | external NFS subdir provisioner | Retain | Immediate | cold/shared storage |

- 22 PVCs are bound across `freelingo`, `gpu-plane`, `speech`, and `synanton`.
- Longhorn scheduling is enabled on node2 and node3 only. Node1 reports `Ready` and
  `AllowScheduling` but has **no disk status**; node0 is not a Longhorn node.
- Available/ maximum Longhorn capacity is approximately:
  - node2: 239.5 GB / 244.9 GB;
  - node3: 240.8 GB / 245.0 GB.
- All four attached Longhorn volumes were `degraded` with 3 requested replicas; detached volumes
  showed `unknown` robustness. A 3-replica policy cannot be healthy with only two disks.
- No Longhorn backup target is configured (`default` backup target is empty), and no backup
  volumes or recurring jobs were present. Do not claim a tested backup/restore path.
- `nfs-cold` is external NFS with `Retain` and `archiveOnDelete=true`; it is not a Loki object
  store and has no observed performance/latency evidence.
- `synanton/minio` exists on a 50Gi local-ssd PV and is already used by the platform. The issue's
  guidance is to avoid introducing object storage without demonstrated need; Stage 1 does not
  require MinIO.

### 4.1 Loki storage decision

Use a dedicated Longhorn StorageClass or volume annotation with **2 replicas** for the Loki PVC.
Do not rely on the default 3-replica Longhorn setting while node1 has no disk. This is a
reduced-durability design: one node failure can make the volume unavailable. Restoring node1
Longhorn capacity is the preferred prerequisite for HA.

Loki single-binary filesystem mode is appropriate for this scale. Do not add an object store or
a distributed Loki topology in Stage 1.

## 5. Ingress, TLS, DNS, and authentication

- Ingress classes: `traefik` and `freelingo`; both use the Traefik controller.
- Traefik is exposed as NodePort (`30443`/`30444` for the app stacks); no LoadBalancer address.
- Existing ingresses:
  - `freelingo/freelingo`: class `freelingo`, no host rule, TLS secret `freelingo-tls`;
  - `speech/speech-demo`: class `traefik`, no host rule, TLS secret `speech-demo-tls`;
  - `longhorn-system/longhorn-ingress`: host `longhorn.local`, no TLS.
- No cert-manager CRDs/issuers are installed. TLS secrets are manual/self-signed.
- No oauth2-proxy, Authentik, Keycloak, or Traefik middleware CRDs are installed.
- No default IngressClass annotation was observed.
- Current operator UIs are exposed through NodePort or hostless ingress; there is no established
  SSO convention for a new observability UI.

### 5.1 Stage 1 access decision

Expose **Grafana only**:

- ClusterIP services for Loki and Alloy; never expose Loki/Alloy directly.
- Grafana behind the existing Traefik ingress with TLS.
- Grafana authentication enabled; anonymous access disabled; secure cookies; local admin secret
  supplied out-of-band, not committed.
- Use a placeholder host such as `grafana.<homelab-domain>` until DNS is confirmed; do not
  assume a public hostname.
- If an internal CA/certificate process is available, use it; otherwise follow the existing
  manual TLS-secret convention and document rotation.
- Add NetworkPolicy allowing only Traefik → Grafana, Grafana → Loki, Alloy → Loki, and DNS.
- Keep Loki and Alloy off the public ingress path.

SSO is desirable but not a Stage 1 prerequisite because no identity provider is installed.
Built-in Grafana auth is the narrowest deployable option; document it as a homelab limitation.

## 6. Existing telemetry and logging

| Capability | Current state |
|---|---|
| Kubernetes log collector | none (no Alloy, Fluent Bit, Vector, Filebeat DaemonSet) |
| Loki / centralized log store | none |
| Grafana in Kubernetes | none |
| Prometheus in Kubernetes | Deployment exists but is scaled to 0; 10Gi Longhorn PVC; config scrapes only `synflux:8090` |
| Alertmanager | not deployed in Kubernetes |
| Tempo / Jaeger in Kubernetes | none |
| Metrics API | unavailable |
| Network-flow UI | Calico Whisker/Goldmane running in `calico-system` |
| Docker Compose observability | Prometheus, Alertmanager, Grafana, Jaeger, Keycloak templates exist in `deployments/docker/compose.yaml`; not the Kubernetes deployment |
| Log retention/backup | no centralized log retention; no Longhorn backup target |

The existing `deployments/observability` Prometheus rules/dashboards and Compose profile are
useful reference assets. They are not a running Kubernetes logging stack.

## 7. Log volume estimate

A one-hour read-only sample of the running `synanton` pods produced:

```text
2,704,960 bytes/hour ≈ 2.70 MB/hour
```

Extrapolated application logs only:

- ≈ 65 MB/day;
- ≈ 0.91 GB/14 days;
- ≈ 1.95 GB/30 days.

This excludes system namespaces, future GPU traffic, repeated restarts, and verbose debug logs.
It also cannot correct for log rotation loss. A 20Gi Loki volume with 14-day retention provides
ample headroom for the observed application rate, but the implementation must enforce a hard
retention/compaction limit and monitor actual ingestion.

The sample was taken while `gpu-plane` was scaled to 0. Once the GPU workloads are restored,
log volume must be re-measured before changing retention or storage size.

## 8. Resource headroom

Known requests from running pods at evidence time:

| Namespace | CPU requests | Memory requests |
|---|---:|---:|
| `kube-system` | 0.85 | 240 MiB |
| `longhorn-system` | 6.72 | 0 MiB |
| `synanton` | 7.95 | 29,440 MiB |
| `calico-system` | 0 | 0 |
| `tigera-operator` | 0 | 0 |

Total cluster requests are roughly 15.5 CPU and 30 GiB, against approximately 60 allocatable
CPU and 203 GiB allocatable memory. Actual utilization is unknown because the Metrics API is
missing. The numbers above are requests, not usage.

Proposed Stage 1 requests/limits:

| Component | Replicas | CPU request | CPU limit | Memory request | Memory limit |
|---|---:|---:|---:|---:|---:|
| Alloy DaemonSet | 4 nodes | 100m each | 200m each | 128Mi each | 256Mi each |
| Loki single binary | 1 | 250m | 1 | 512Mi | 1Gi |
| Grafana | 1 | 100m | 500m | 256Mi | 512Mi |
| **Total** | | **750m** | **2.3** | **1.25Gi** | **2.5Gi** |

This is a small footprint relative to observed request headroom. Scheduling must still tolerate
the node0 control-plane taint if node0 logs are collected; otherwise the Alloy DaemonSet runs on
node1–node3.

## 9. Proposed Stage 1 architecture

```text
pod stdout/stderr
  -> kubelet/containerd /var/log/pods
    -> Alloy DaemonSet (read-only hostPath, K8s API discovery)
      -> Loki ClusterIP (single binary, filesystem PVC, 14d retention)
        -> Grafana ClusterIP (Explore + dashboards)
          -> Traefik ingress + TLS + authenticated operator
```

### 9.1 Namespace and access

- New `observability` namespace.
- Dedicated ServiceAccounts:
  - Alloy: read-only pods/namespaces/nodes discovery;
  - Loki: no Kubernetes API access needed;
  - Grafana: no Kubernetes API access needed.
- Least-privilege ClusterRole/ClusterRoleBinding only for Alloy discovery.
- Default-deny NetworkPolicy; explicit DNS, Traefik, Grafana/Loki, and Alloy/Loki paths.
- No privileged containers; read-only root filesystems where possible; Alloy hostPath mounts
  read-only.
- ResourceQuota and LimitRange to prevent the stack from consuming unbounded cluster capacity.

### 9.2 Collection scope

Initial target namespaces:

- `synanton` (application, extraction, graph, storage);
- `gpu-plane` (Gateway/GPU Runtime when active);
- `observability` (self-observation);
- optionally `kube-system` after confirming volume and sensitivity.

Do not collect unrelated tenants by default. If `synanton` and other application namespaces are
shared by multiple tenants, define tenant-aware access before exposing tenant logs.

### 9.3 Labels, parsing, and correlation

Low-cardinality labels only:

```text
cluster=homelab
namespace
service / app
workload
container
node
environment=homelab
```

High-cardinality identifiers stay in the structured log body or Loki structured metadata:

```text
trace_id, span_id
tenant_id
document_id, chunk_id
extraction_attempt_id
gateway_request_id, gateway_execution_id
ontology_release_id, ontology_release_digest
contract_digest
```

Use `loki.process` JSON extraction where applications emit structured JSON. Unstructured logs
must remain searchable but do not need synthetic fields that the application did not emit.
Never invent Gateway/extraction identifiers.

### 9.4 Retention and storage

- Loki filesystem storage on a 20Gi dedicated Longhorn volume.
- 2 Longhorn replicas, explicitly configured.
- 14-day retention with compactor and a hard storage limit.
- Reject old samples; bound query length and stream count.
- Single binary, no object store, no distributed components.
- Grafana application data: small Longhorn volume (2Gi) or ephemeral SQLite with dashboards
  provisioned from Git; dashboards and datasources remain Git-managed.

### 9.5 Failure isolation

- Alloy is best-effort and must not block pod startup or application I/O.
- If Loki is unavailable, logs may be dropped/buffered within a bounded limit; applications
  continue.
- If Grafana is unavailable, logging continues; operator UI is not on the ingestion path.
- The stack must have independent requests/limits and must not share a process with Gateway or
  extraction workloads.
- Document dropped-log/backpressure behavior in the Stage 1 runbook.

### 9.6 Backup, restore, and retention expectations

- Logs are operational data, not a system of record; do not promise Longhorn backup for Loki
  until a backup target is configured and tested.
- Configuration (manifests, dashboards, datasources, rules) is Git-managed and restorable.
- Grafana local database is either disposable or backed up separately; dashboards are
  provisioned from Git.
- Teardown must remove the namespace, PVCs, and generated secrets while preserving audit notes.
- The current cluster has no Longhorn backup target; state this limitation explicitly.

## 10. Security, privacy, and retention

- No secrets, tokens, authorization headers, private keys, or registry credentials in logs.
- No full document text, raw prompts, or raw model responses in Loki by default.
- LLM interaction artifacts belong in the separate protected artifact model defined in
  `docs/implementation/it-books/llm-interaction-trace-contract.md`, not Loki.
- Tenant isolation must be defined before tenant-specific logs or artifacts are exposed.
- Access to Grafana is authenticated; no anonymous public ingress.
- Define per-namespace retention and deletion before onboarding additional tenants.
- Redact/avoid sensitive payloads at the application/logging contract level, not only in Grafana.
- Keep high-cardinality IDs out of Loki stream labels.
- Record access/retention policy in the Stage 1 runbook.

## 11. Deployment conventions and #889

The existing homelab uses:

- Kustomize overlays under `deployments/homelab/k8s/`;
- lifecycle scripts (`deploy-demo.sh`, `start.sh`, `stop.sh`, `verify-demo.sh`, `remove.sh`);
- image mirroring to `local-registry:5000`, with pinned image digests for the demo;
- Helm for Longhorn and the NFS provisioner.

Stage 1 should follow the same conventions as **independent observability manifests**, not
duplicate or modify the Deployment Configurator (#889 / merged lifecycle fix #890). It must not
change #889's service lifecycle semantics. If observability is later added to a deployment
profile, coordinate the profile representation rather than copying lifecycle scripts.

## 12. Stage 1 implementation plan (not executed)

1. Resolve or explicitly accept the Longhorn 2-replica decision; verify volume health.
2. Clean up stale GPU pods and re-establish/confirm workload scope before log sizing.
3. Add an `observability` Kustomize overlay with pinned Alloy/Loki/Grafana versions and digests.
4. Mirror images and verify registry access from each target node.
5. Add namespace, ServiceAccounts, RBAC, NetworkPolicies, PVCs, deployments/DaemonSet.
6. Add Grafana provisioning for Loki datasource and initial dashboards.
7. Configure TLS ingress and authenticated access using the cluster's actual convention.
8. Install and verify on a narrow scope; measure ingestion for at least 24 hours.
9. Run persistence/restart, query, security, and failure-isolation checks.
10. Publish installation, upgrade, troubleshooting, backup/restore, retention, and teardown
    runbooks.

Do not deploy Prometheus/Tempo in Stage 1. Do not add raw prompt/response storage.

## 13. Stage 1 acceptance evidence plan

Future evidence must include:

- `kubectl get pods -n observability -o wide`;
- Alloy collection status and target namespace list;
- Loki ingestion rate and on-disk usage;
- Grafana Explore query results filtered by namespace/workload/pod/container;
- persistence after Loki pod restart and cluster restart;
- retention/compaction evidence;
- resource usage against requests/limits;
- NetworkPolicy and ingress/auth checks;
- behavior with Loki stopped (application unaffected);
- no secrets/raw prompts in sampled logs;
- no changes to #919, A2, IT Books v1, CSO, graph, or official metrics.

## 14. Open questions / blockers

1. Restore node1 Longhorn disk or accept 2-replica degraded durability for Loki?
2. What hostname/DNS and TLS issuance convention should Grafana use?
3. Is the Grafana admin account sufficient, or is an identity provider expected later?
4. Which namespaces are in the initial collection scope?
5. How should stale `gpu-plane` pods be cleaned up, and when will GPU workloads return?
6. What is the approved retention/access model for tenant-specific logs?
7. Should the existing Prometheus deployment be restored in Stage 1 or left for Stage 3?
8. Is a Longhorn backup target planned, or is Loki explicitly non-backed-up operational data?

## 15. Boundaries

No deployment, upgrade, scale, cleanup, secret rotation, or workload change was performed.
This document does not modify #919, A2, IT Books v1, CSO, graph state, evaluation metrics,
P2/Docling, or #889 lifecycle behavior.
