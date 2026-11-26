# #924 Stage 1 readiness gates

**Status:** Readiness review; deployment NOT authorized
**Evidence date:** 2026-11-26
**Prior:** Stage 0 design #926 merged `c257d89e`; security/operations review #927 merged `4808fbdd`

## 1. Gate summary

| Gate | Status | Evidence / blocker |
|---|---|---|
| Grafana DNS/TLS identity | Decision recorded; operator DNS/cert issuance pending | `grafana.homelab.local`; internal CA; 1-year server cert; rotate 30 days before expiry |
| LAN-only exposure | Design recorded; perimeter control pending | Traefik NodePort on private `192.168.10.0/24`; no public NAT/DNS evidence; firewall rule is external to cluster |
| Backup target | Approved target chosen; export not configured | Dedicated WD6000 path exists and is writable; NFS export requires node1 root |
| Backup/restore proof | **Blocked** | No Longhorn backup target or restore has been performed |
| NetworkPolicy/RBAC | Proposed; not deployed/tested | No `observability` namespace exists; no traffic test possible without deployment |
| Tenant-log isolation | Policy recorded; access model pending | no tenant-specific logs may be exposed before tenant-scoped access |
| Retention/resource limits | Calculated; verification pending deployment | 2.7 MB/hour sample; 20Gi/14d Loki; CPU/memory table below |
| Lifecycle stop/start | Defined; not tested | Stage 1 not deployed; full cluster-stop not run |
| Deployment authorization | **Not granted** | explicit user authorization required |

## 2. Ingress identity, TLS, and LAN-only exposure

### 2.1 Decision

- Hostname: `grafana.homelab.local`.
- Certificate source: internal self-signed CA, generated out-of-band; server certificate
  `CN=grafana.homelab.local`, SAN `DNS:grafana.homelab.local`, ECDSA P-256, `CA:FALSE`.
- Validity: 1 year; rotate 30 days before expiry. No cert-manager is installed, so renewal is
  manual; do not claim automatic rotation.
- TLS secret: `grafana-tls` in namespace `observability`.
- Ingress class: `traefik`; websecure entrypoint; only Grafana is exposed.
- Loki and Alloy remain ClusterIP-only.

Local certificate-shape validation was performed with a temporary CA/server pair:
`subject=CN=grafana.homelab.local`, `issuer=CN=Synanton Homelab Internal CA`,
`CA:FALSE`, `openssl verify ... OK`. No certificate or key was stored in the repository.

### 2.2 Remaining operator actions

1. Add `grafana.homelab.local` to the LAN DNS or client hosts file.
2. Ensure the Traefik NodePorts `30443`/`30444` are reachable only from the trusted LAN;
   there is no in-cluster source-IP control for NodePort, so this is a firewall/router action.
3. Issue the server certificate and create the `grafana-tls` Secret after deployment authorization.
4. Confirm no public DNS record, NAT rule, or port-forward exists.

### 2.3 Proposed ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana
  namespace: observability
spec:
  ingressClassName: traefik
  tls:
    - hosts: [grafana.homelab.local]
      secretName: grafana-tls
  rules:
    - host: grafana.homelab.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: grafana
                port:
                  number: 3000
```

### 2.4 Verification procedure (after deployment)

- From LAN: TLS SAN matches; certificate chain validates against the internal CA.
- HTTP is redirected or rejected; no plaintext login.
- Anonymous access is disabled; unauthenticated request shows only the login page.
- From a non-LAN/WAN path: connection must be blocked at the perimeter.
- WAN/public exposure is **not approved** without an SSO/MFA front door.

## 3. Dedicated backup target and restore proof

### 3.1 Approved target

Target: `192.168.10.31:/mnt/WD6000/longhorn-backup`.

Current evidence:

- node1 `/mnt/WD6000` is `/dev/sdb1` ext4, mounted from `/etc/fstab` entry
  `UUID=b2cd5a9b-1a68-4152-b8fc-8cf0b29cd6d7 /mnt/WD6000 ext4 defaults 0 0`;
- `/mnt/WD6000/longhorn-backup` created, mode `770`, owner `aminin:aminin`;
- current user can write the directory;
- the directory is **not yet NFS-exported**; `/etc/exports` has only the personal
  `/mnt/WD6000/nfs_share` export.

The personal share and the root-filesystem `nfs-cold` path remain forbidden as backup targets.

### 3.2 Required operator action (root on node1)

```bash
# Review before applying. Restrict to the trusted LAN.
echo '/mnt/WD6000/longhorn-backup 192.168.10.0/24(rw,sync,no_subtree_check,no_root_squash)' \
  | sudo tee -a /etc/exports
sudo exportfs -ra
sudo exportfs -v | grep longhorn-backup
```

Do not add a broader CIDR. Do not use `nfs_share`.

### 3.3 Proposed Longhorn configuration

After the export is verified:

```text
backupTargetURL = nfs://192.168.10.31:/mnt/WD6000/longhorn-backup
credentialSecret = ""
```

Do not set this configuration until the export and network path are approved.

### 3.4 Restore proof (required before any recoverability claim)

1. Configure the backup target.
2. Create a 1Gi test volume using `longhorn-observability` (2 replicas).
3. Attach a test pod; write a file and record its SHA-256.
4. Take a Longhorn snapshot and create a backup; wait for completion.
5. Delete the test PVC/PV/volume and verify the data is gone.
6. Restore from the backup into a fresh test volume.
7. Verify the file SHA-256 and the restored volume health.
8. Delete all test resources, including the backup if retention permits.

**Status: BLOCKED.** None of steps 1–8 has been performed. Loki is not recoverable.

## 4. NetworkPolicy, RBAC, and tenant-log isolation

No `observability` namespace exists and no Stage 1 resources are deployed. The following is the
proposed control set; traffic tests require deployment authorization.

### 4.1 Service accounts and RBAC

- `alloy`: read-only get/list/watch on pods, namespaces, nodes; no Secrets.
- `loki`: no Kubernetes API permissions.
- `grafana`: no Kubernetes API permissions.
- No cluster-admin binding.

### 4.2 NetworkPolicy

- Default deny ingress/egress in `observability`.
- Allow DNS egress.
- Allow Traefik → Grafana.
- Allow Grafana → Loki.
- Allow Alloy → Loki.
- Allow Alloy → Kubernetes API for discovery.
- Do not expose Loki/Alloy via Ingress or NodePort.
- Tenant-specific log access remains disallowed until a tenant-scoped access model exists.

### 4.3 Verification

- `kubectl auth can-i` checks for each service account after deployment.
- NetworkPolicy probes from a non-allowed namespace must fail.
- Grafana/Loki must not be reachable from arbitrary namespaces.
- Not tested today; no namespace or workloads exist.

## 5. Retention, storage, and resource limits

Observed one-hour Synanton log sample: **2,704,960 bytes/hour** (about 65 MB/day,
0.91 GiB/14 days) before system namespaces and GPU workloads. A 20Gi Loki volume with
14-day retention has headroom, subject to actual post-deployment measurement.

| Component | Requests | Limits |
|---|---|---|
| Alloy DaemonSet (4 nodes) | 400m CPU / 512Mi | 800m / 1Gi |
| Loki single binary | 250m / 512Mi | 1 CPU / 1Gi |
| Grafana | 100m / 256Mi | 500m / 512Mi |
| **Total** | **750m / 1.25Gi** | **2.3 CPU / 2.5Gi** |

Proposed ResourceQuota: requests.cpu `1`, requests.memory `2Gi`, limits.cpu `3`,
limits.memory `3Gi`, persistentvolumeclaims `3`, requests.storage `25Gi`.

Loki: 20Gi PVC on `longhorn-observability`, 14-day retention, compactor, hard storage limit.
Grafana: 2Gi or ephemeral with Git-provisioned dashboards.

The Metrics API is unavailable; actual usage and ingestion must be measured after deployment.
Retention/compaction is not backup.

## 6. Observability-specific lifecycle

Stage 1 must have its own lifecycle path; it must not require a full cluster stop.

Proposed stop:

1. Remove/disable Alloy collection.
2. Scale Loki and Grafana to zero.
3. Leave PVCs and configuration intact.
4. Confirm Synanton/Gateway workloads are unaffected.

Proposed start:

1. Scale Loki and Grafana up.
2. Wait for Loki readiness and PVC mount.
3. Restore Alloy collection.
4. Verify recent logs and Grafana login.

This is **not tested**; no deployment exists. A full cluster-stop remains a separate operation
and is not part of Stage 1 acceptance unless explicitly authorized.

## 7. Operator decisions still required

1. Apply the dedicated NFS export on node1 (root action).
2. Approve the firewall/DNS changes for LAN-only `grafana.homelab.local`.
3. Issue the internal TLS certificate and create the secret at deployment time.
4. Approve NetworkPolicy/RBAC/test scope after deployment authorization.
5. Authorize the backup/restore test; configure the backup target only after export approval.
6. Explicitly authorize Stage 1 deployment.

## 8. Boundaries

No Stage 1 namespace, pod, Ingress, StorageClass, NetworkPolicy, RBAC, TLS secret, backup target,
or backup/restore operation was created. The WD6000 directory and its fstab-backed mount were
inspected; only the dedicated directory was created. The personal `nfs_share` and `nfs-cold`
paths are not used.

No #919 baseline, A2, IT Books v1, CSO, ontology, graph, GPU-runtime, or evaluation state was
changed. The untracked GPU extraction blueprint remains excluded.
