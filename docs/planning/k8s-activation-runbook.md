# K8s activation runbook — RAKG / #589

**Baseline at authoring:** `main` = `948428a` (local RAKG activation #601 + glibc runtime images #604).
**Activation head:** record `git rev-parse HEAD` in the acceptance file; if the tree differs from the baseline, use the activation head for every build and evidence run.
**Gate:** merge != activation != migration. Flags stay false until the evidence below is captured on the exact running tree.

## 0. Readiness blockers (current manifests)

The overlay now declares the RAKG backing services:

- **Neo4j** — StatefulSet, Service, and PVC in `local-real-demo/neo4j.yaml`.
- **Relix** — Deployment and Service in `local-real-demo/relix.yaml`, port **8084**.
- **Syntology** — Deployment, Service, PVC, and a Postgres DB-init container in `local-real-demo/syntology.yaml`.
- **NetworkPolicies** — `rak-network-policies.yaml`; the gpu-plane exceptions remain `tei-embedding-access.yaml` and `vllm-extraction-access.yaml`.
- **Images** — Relix and Syntology are not yet pinned to registry digests in the overlay; build/push and pin them before applying. Neo4j must be mirrored.
- **vLLM extraction and TEI embedding manifests are not in this repository**; `vllm-extraction` was manually deployed to `gpu-plane` and must be exported/recorded before teardown.
- A live Entity Graph Playwright spec does not exist yet; #599 tracks it. The current Playwright entity-graph spec uses route interception and is not a live E2E.

Do not flip consumer flags to discover missing services at runtime.

## 1. Pre-flight

### Cluster and images

- [ ] `kubectl get nodes` is healthy; no stale `UnexpectedAdmissionError` pods.
- [ ] Registry `local-registry:5000` is reachable (`deployments/homelab/scripts/check-registry.sh`).
- [ ] vLLM extraction is running in `gpu-plane`; record the deployment spec/image and service endpoint. Health: `kubectl -n gpu-plane get deploy,svc vllm-extraction`.
- [ ] TEI embedding is running in `gpu-plane`; record deployment/image/service. Health: `kubectl -n gpu-plane get deploy,svc tei-embedding`.
- [ ] Neo4j is deployed with a persistent volume; record URI, database, and credentials source.
- [ ] Syntology is deployed; record service URL and ontology version/data digest. The overlay seeds `sample-ontology.ttl` as version `1.0.0` for tenant `demo` when the Syntology DB is empty; if it is not empty, create/activate the intended version explicitly.
- [ ] Cassandra is running (`synanton` namespace) and the keyspace exists.

### Build and push at the activation head

Use the commit that contains this runbook and record it:

```bash
cd /home/aminin/workspace/synanton/platform
export ACTIVATION_HEAD="$(git rev-parse HEAD)"
export TAG="$(git rev-parse --short HEAD)"
test "$ACTIVATION_HEAD" = "$(git rev-parse main)" || echo "WARNING: not building main HEAD"

# Java services with existing Dockerfiles
docker build -f deployments/docker/synflux.Dockerfile         -t local-registry:5000/synanton/synflux:$TAG .
docker build -f deployments/docker/synquest.Dockerfile        -t local-registry:5000/synanton/synquest:$TAG .
docker build -f deployments/docker/syntology.Dockerfile       -t local-registry:5000/synanton/syntology:$TAG .
docker build -f deployments/docker/syntology-admin.Dockerfile -t local-registry:5000/synanton/ui:$TAG .
```

The Relix prebuilt image packages `:java:relix:bootJar`:

```bash
docker build -f deployments/docker/relix.prebuilt.Dockerfile \
  -t local-registry:5000/synanton/relix:$TAG .
```

Or use the mirror script with the manifest tag:

```bash
IMAGE_TAG=local-real-demo ./scripts/mirror-images.sh
```

Push all images and pin the deployment specs to the resulting digests (not mutable tags):

```bash
for image in synflux synquest syntology ui relix; do
  docker push local-registry:5000/synanton/$image:$TAG
done
```

Record the digests in the acceptance file.

## 2. Configuration

### Required flags

| Service | Variable | Value |
|---|---|---|
| synflux | `EXTRACTION_PROVIDER` | `llm` |
| synflux | `EXTRACTION_CONSUMER_ENABLED` | `true` |
| synflux | `EXTRACTION_GRAPH_ENABLED` | `true` |
| synquest | `RESOLUTION_CONSUMER_ENABLED` | `true` |
| synflux | `PROMOTION_PRODUCER_ENABLED` | `true` |
| relix | `PROMOTION_CONSUMER_ENABLED` | `true` |
| relix (for #589 reconciliation evidence) | `RECONCILIATION_ENABLED` | `true` |

Also set consistently:

- Tenants: `EXTRACTION_CONSUMER_TENANT`, `RESOLUTION_CONSUMER_TENANT`, `PROMOTION_CONSUMER_TENANT`, `RECONCILIATION_TENANT` (use the same tenant, e.g. `demo`).
- URLs: `LLM_EXTRACTION_URL` → vLLM service; `RELIX_BASE_URL` → `http://relix.synanton.svc.cluster.local:8084`; `SYNTOLOGY_BASE_URL` → Syntology service. The overlay now uses 8084 consistently.
- `SYNANTON_JWT_SECRET` in synflux, synquest, relix, and Syntology from the same Kubernetes Secret. Never place it in a ConfigMap or evidence file.
- `NEO4J_PASSWORD`/`neo4j-cred` and `POSTGRES_PASSWORD`/`postgres-cred` are required; `deploy-demo.sh` creates both secrets from `config/.env`.
- Neo4j/Cassandra connection env for Relix; persistence paths; `RELIX_TENANT`.
- `EXTRACTION_CONSUMER_ENABLED`/graph writes remain false in the default manifests; activation only via an explicit overlay or `kubectl set env` that is recorded.
- The UI nginx proxy (`/api/v1/entities/` → Relix) must target the same service/port as the other callers; `X-Tenant-ID` must be stripped and Authorization forwarded.

### Migrations

- [ ] Startup applies V1–V26 idempotently. V22–V24 add resolution state/results; V25 claim/lease/fencing columns; V26 `resolution_results.search_generation`.
- [ ] Verify columns in Cassandra:
  ```bash
  kubectl -n synanton exec cassandra-0 -- cqlsh -e \
    "SELECT column_name FROM system_schema.columns WHERE keyspace_name='ingestion_cache' AND table_name='resolution_results';"
  ```
  Require `claim_status`, `claim_owner_id`, `lease_expires_at`, `attempt_count`, `search_generation`.
- [ ] Verify no migration errors/failed startup; capture logs.

## 3. #592 rerun rule

- If #592 is **not merged**: evidence from this runbook applies only to the current transitional Neo4j seam at the recorded activation head. Do not treat it as evidence for #592.
- If #592 **is merged before activation**: run the full #589 suite and live #599 E2E on the migrated tree.
- If #592 **merges after activation**: #589 remains open; re-run the full fault-injection suite and live E2E on the post-#592 tree before declaring activation complete or keeping flags enabled.
- The acceptance file must name the exact commit/tree, images, and whether the seam was pre- or post-#592.

## 4. #599 live E2E execution

1. **Seed canonical entities and project them** (the resolver needs `:Entity`
   nodes; the graph writer does not create canonical entities):
   ```bash
   kubectl -n synanton exec -i neo4j-0 -- sh -c \
     'cypher-shell -u neo4j -p "${NEO4J_AUTH#*/}"' \
     < demo-data/rak-halcyon-entities.cypher
   # Port-forward Neo4j HTTP and Synquest, then run the projection helper:
   kubectl -n synanton port-forward svc/neo4j 17474:7474 >/tmp/neo4j-pf.log 2>&1 &
   kubectl -n synanton port-forward svc/synquest 18083:8083 >/tmp/synquest-pf.log 2>&1 &
   NEO4J_HTTP_URL=http://localhost:17474 NEO4J_USER=neo4j \
     NEO4J_PASSWORD="$NEO4J_PASSWORD" SYNQUEST_URL=http://localhost:18083 \
     SYNANTON_JWT_SECRET="$SYNANTON_JWT_SECRET" TENANT=demo \
     python3 tools/entity-projection/project_entities.py
   ```
   Require all nine entities projected. `deployments/docker/rak-halcyon-entities.cypher`
   is the same fixture used by the local Compose smoke.
2. **Upload a known document** through the synflux ingestion API (multipart `/v1/ingestion-runs`, or the existing local-real-demo script as a reference). Use a known source ID, tenant, and document digest.
2. **Poll status** with a tenant token:
   ```bash
   export SYNFLUX_URL="http://<synflux-service>:8090"
   export SOURCE_ID="<source-id>"
   export TOKEN="<tenant-scoped-jwt>"
   curl -fsS -H "Authorization: Bearer $TOKEN" \
     "$SYNFLUX_URL/v1/sources/$SOURCE_ID/status" | jq
   ```
   Require all three:
   - `extraction_status == "KNOWLEDGE_READY"`
   - `graph_status == "GRAPH_COMMITTED"`
   - `resolution_status == "COMPLETED"`
3. **Run the live Entity Graph E2E** from #599 against the cluster UI URL. The local smoke queries `entity-auxiliary-coolant-loop` because that fixture owns the `tested_every` edge; use an entity with a real promoted relation. The mocked route-interception suite is not a live E2E. Record the Playwright JSON/HTML report. If the live spec is not yet present, activation is blocked on #599.
4. **Manually verify the UI**: open the Entity Graph tab for an entity from the new document; confirm incoming/outgoing arrows, evidence chunk/version/offsets, and no truncation/error state. Capture screenshots and the entity ID.
5. **Verify detail**:
   - Neo4j has one active `:RESOLVES_TO` per expected mention, `:REL`/`:RelationEvidence` from promotion, and `:RESOLVES_TO.search_generation` is populated.
   - Cassandra resolution rows are `COMPLETED` with `search_generation`.
   - No duplicate evidence; no cross-tenant data.

## 5. Rollback / teardown

Immediately disable background work; do not delete data:

```bash
kubectl -n synanton set env deploy/synflux EXTRACTION_CONSUMER_ENABLED=false EXTRACTION_GRAPH_ENABLED=false
kubectl -n synanton set env deploy/synquest RESOLUTION_CONSUMER_ENABLED=false
kubectl -n synanton set env deploy/relix PROMOTION_CONSUMER_ENABLED=false RECONCILIATION_ENABLED=false
kubectl -n synanton rollout status deploy/synflux deploy/synquest deploy/relix
```

- [ ] Confirm no consumer/reconciler activity in logs.
- [ ] Preserve Cassandra/Neo4j PVCs and the document/evidence.
- [ ] If an image is bad, roll back to the previous Deployment revision; keep flags false.
- [ ] Do not remove PVCs or the cluster until the acceptance record is committed.

## 6. Evidence collection

Create `docs/acceptance/k8s-activation-YYYY-MM-DD.md` and link it from #589, #599, and #508.

Record:

- activation commit, branch, image tags **and digests**;
- kubectl context/cluster and namespace;
- status JSON for the uploaded source (redact tokens);
- Playwright report path and result;
- screenshots of the Entity Graph with evidence panel and any truncated/empty/error state;
- Neo4j/Cassandra verification queries and results;
- migration logs and flag values;
- #592 state (pre/post) and rerun result;
- rollback status if any.

Store artifacts under `docs/acceptance/artifacts/k8s-activation-YYYY-MM-DD/` (logs, JSON, screenshots). Never store JWTs, passwords, or provider keys.

## Success definition

#589 is closed only when the live upload → extraction → graph commit → resolution → promotion → Entity Graph → evidence journey succeeds on the recorded tree, the #592 rerun rule is satisfied, and the acceptance record is linked. Green component tests alone are not activation.
