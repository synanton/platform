# #924 Stage 1 — security and operations review

**Status:** Review, documentation-only
**Stage 1 deployment:** not authorized
**Related:** #924, merged PR #926 (`c257d89e`), PR #34 (GPU lifecycle)
**Storage:** node2/node3 2-replica Longhorn policy validated; node1 excluded
**Backup/recovery:** blocked; no restore proof

## 1. Review outcome

The Stage 1 design is technically deployable, but it is **not approved for deployment** until the
security, exposure, retention, resource, and operational checks below are accepted. Merging this
review would not authorize deployment.

The design remains:

- Alloy DaemonSet → Loki ClusterIP (single binary, filesystem PVC) → Grafana ClusterIP;
- Grafana only behind Traefik with TLS and authenticated access;
- Loki/Alloy never exposed through ingress or NodePort;
- 20Gi Loki volume using the dedicated 2-replica policy, 14-day retention;
- no raw prompts/responses, secrets, or full document text in Loki;
- no Prometheus or Tempo dependency in Stage 1.

## 2. Security model

### 2.1 Threat model

| Risk | Required control |
|---|---|
| Unauthenticated operator access | Grafana login required; anonymous disabled; no public anonymous ingress |
| Tenant log crossover | tenant-aware access before exposing tenant logs; no shared anonymous viewer |
| Credential leakage | secrets only in Kubernetes Secrets; no auth headers/tokens/keys in logs |
| Sensitive content in logs | no full document text/raw prompts by default; redact/avoid at app logging boundary |
| Grafana/Loki exposure | Grafana only via TLS ingress; Loki/Alloy ClusterIP only; NetworkPolicy |
| Log injection/abuse | structured parsing; bounded fields; query limits; no privileged containers |
| Disk fill / DoS | retention + compaction + size limit + resourceQuota/limitRange |
| Supply-chain drift | pinned image/app versions and digests; no `latest` |
| Certificate expiry | documented TLS secret rotation and expiry monitoring |
| Broad collector permissions | Alloy read-only RBAC, not cluster-admin |

### 2.2 Authentication and authorization

- Grafana authentication is mandatory. Anonymous access is disabled.
- Stage 1 may use Grafana local users (admin + viewer) because no SSO/OIDC/LDAP provider is
  installed. This is a homelab limitation, not an enterprise SSO claim.
- Admin credentials are generated out-of-band and stored in a Secret, never in Git.
- Operator access is read-only by default; admin is limited to installation/troubleshooting.
- Tenant-specific log exposure is not authorized until tenant-scoped access is designed.
- Future SSO (oauth2-proxy/Authentik/Keycloak) is a separate change; no provider is installed
  today.

### 2.3 TLS and ingress exposure

Observed cluster state:

- Traefik is exposed as NodePort (`30443`/`30444` for the app stacks).
- Ingress classes `traefik` and `freelingo` exist; no default class is set.
- No cert-manager CRDs/issuers are installed.
- Existing app TLS secrets are manual/self-signed; the freelingo certificate is self-signed and
  valid to 2029.
- No oauth2-proxy, Authentik, Keycloak, or Traefik middleware CRDs are installed.

Stage 1 requirements:

- Only Grafana is exposed; Loki and Alloy remain ClusterIP.
- Grafana ingress uses TLS and a hostname explicitly chosen by the operator. A placeholder such
  as `grafana.<homelab-domain>` must not be treated as DNS/TLS approval.
- Reuse the existing manual TLS convention or an internal CA; document certificate issuance,
  trust, and rotation. Do not claim automatic renewal.
- Configure HTTP→HTTPS behavior at Traefik or reject unauthenticated plain HTTP explicitly.
- No unauthenticated public exposure. If remote access is required, add an authenticated access
  layer outside Grafana as a separate reviewed change.

### 2.4 NetworkPolicy

The observability namespace must use default-deny for ingress and egress, with explicit allows:

- DNS egress;
- Traefik controller → Grafana;
- Grafana → Loki;
- Alloy → Loki;
- Alloy → Kubernetes API for discovery;
- Loki → persistent volume only (no external egress required).
- Grafana/Loki must not be reachable directly from arbitrary namespaces.

Alloy hostPath mounts are read-only. No privileged containers, host networking, or host PID.

### 2.5 RBAC and service accounts

- Alloy: read-only pods/namespaces/nodes discovery plus its own namespace resources.
- Loki: no Kubernetes API permissions.
- Grafana: no Kubernetes API permissions.
- No reuse of platform/service-account credentials.
- No wildcard cluster-admin binding.

## 3. Privacy and log-content review

Stage 1 must not become a content archive.

- Do not log secrets, tokens, authorization headers, private keys, or registry credentials.
- Do not log full document text, raw prompts, or raw model responses by default.
- Keep high-cardinality IDs in structured metadata/body, not Loki stream labels.
- Tenant identifiers require a tenant-aware access model before exposure.
- If application logs already contain document content, fix the logging boundary or redact before
  onboarding that service.
- LLM interaction artifacts remain in the separately protected model from
  `docs/implementation/it-books/llm-interaction-trace-contract.md`, not Loki.

## 4. Retention and resource review

| Item | Proposed value | Review note |
|---|---|---|
| Loki retention | 14 days | explicit compactor retention; no infinite retention |
| Loki PVC | 20Gi | dedicated 2-replica Longhorn policy; no HA claim |
| Loki hard limit | required in implementation | disk-full behavior must drop/limit, not block apps |
| Grafana PVC | 2Gi or ephemeral | dashboards/datasources provisioned from Git |
| Alloy per node | 100m/128Mi request; 200m/256Mi limit | 4-node DaemonSet; node0 taint tolerance if collected |
| Loki | 250m/512Mi request; 1 CPU/1Gi limit | single binary |
| Grafana | 100m/256Mi request; 500m/512Mi limit | single replica |
| Total requests | ~750m CPU, ~1.25Gi | small relative to observed request headroom |
| Total limits | ~2.3 CPU, ~2.5Gi | enforce with ResourceQuota/LimitRange |

These are **requests/limits**, not measured usage. The Metrics API is unavailable; Stage 1 must
measure actual ingestion and resource use before increasing retention or size.

Retention/compaction is not backup. No Loki volume is recoverable until a dedicated backup target
and a successful restore are demonstrated.

## 5. Failure and operations review

### 5.1 Failure isolation

- Alloy is best-effort; Loki/Grafana failure must not block ingestion, extraction, or Gateway.
- Alloy buffer/drop behavior is bounded; document dropped logs.
- Loki disk-full must fail queries/writes gracefully; applications continue.
- Grafana outage affects only the UI.
- Observability resources must not compete with Gateway/GPU workloads for GPU resources.

### 5.2 Required runbook content

- install/upgrade/rollback;
- image/config pinning and registry prerequisites;
- health checks and expected pod/Service state;
- Loki disk-full and retention/compaction behavior;
- Alloy collection failure and dropped-log diagnosis;
- Grafana auth/TLS/certificate rotation;
- NetworkPolicy troubleshooting;
- namespace teardown and PVC retention;
- backup/recovery gate (currently blocked; no recoverable claim).

### 5.3 Restart and recovery

- PVC persistence after Loki pod restart;
- cluster restart with nodes cordoned/uncordoned using existing homelab lifecycle;
- Grafana configuration restoration from Git;
- no reliance on full cluster-stop for a test; full stop requires an explicit maintenance window.

## 6. Stage 1 acceptance checklist

- [ ] Final image/chart/app versions and digests pinned; no `latest`.
- [ ] Grafana TLS hostname, certificate source, trust, and rotation approved.
- [ ] Anonymous Grafana access disabled; admin secret out-of-band; viewer/admin roles defined.
- [ ] HTTP→HTTPS behavior approved.
- [ ] Loki/Alloy ClusterIP only; no direct ingress/NodePort.
- [ ] Default-deny NetworkPolicy with only required paths verified.
- [ ] Alloy RBAC read-only; no privileged/host-network pods.
- [ ] 14-day retention, compactor, and hard size limit configured.
- [ ] ResourceQuota/LimitRange applied; requests/limits match the table.
- [ ] Loki volume healthy on the dedicated 2-replica policy; no HA claim.
- [ ] Restart persistence verified.
- [ ] Failure-isolation test: Loki down does not affect Synanton/Gateway.
- [ ] No secrets/raw prompts/full document text in sampled logs.
- [ ] Dedicated backup target, access controls, and successful sample restore demonstrated, or
      explicit written acceptance that logs are non-recoverable (currently not acceptable for a
      recoverable claim).
- [ ] Full stop/start interaction tested in an approved maintenance window or explicitly
      excluded.
- [ ] Untracked `extraction-llm.yaml` remains excluded from the evaluation pin.

## 7. Open decisions

1. Actual Grafana DNS hostname and TLS issuance path.
2. Local Grafana users vs. deferring Stage 1 until SSO is available.
3. Whether remote access is required; if yes, which authenticated layer.
4. Exact Loki retention/compaction and disk-full behavior.
5. Approved dedicated backup target and restore procedure.
6. Whether full cluster-stop interaction is in Stage 1 acceptance or explicitly excluded.
7. Tenant-aware access expectations before onboarding tenant logs.

## 8. Boundaries

No deployment, StorageClass application, backup configuration, extraction, or protected-state
change is included. Stage 1 remains unauthorized. The #919 baseline, A2, CSO, ontology, and graph
state remain unchanged.
