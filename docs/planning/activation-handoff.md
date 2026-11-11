# RAKG activation handoff

**Status:** locally proven; K8s activation blocked on network reachability.
**Last updated:** 2026-11-11
**Current main at authoring:** `de4373f`

## What is done

The RAKG pipeline is code-complete and proven end-to-end on the isolated Docker
Compose stack:

```text
upload -> extraction -> graph commit -> resolution -> validation -> promotion
       -> Relix Entity Graph with relation evidence
```

Evidence:

- Live Compose Entity Graph E2E: **1 passed in 39.7s** (`test/e2e/tests/entity-graph.live.spec.ts`, PR #606).
- Mocked Entity Graph suite: **5 passed**.
- K8s manifests render with `kubectl kustomize`; `kubeconform -strict -kubernetes-version 1.30.0` reports **27/27 valid** for the overlay and **2/2 valid** for the gpu-plane policy files.
- Runtime integration fixes are merged: Jackson alignment, snake_case DTOs, service JWT for Syntology validation, Syntology V3 migration/index fix, Synquest consumer property prefix, and the #583 promotion producer (PRs #601/#604/#605).
- The RAKG backing services are declared in the `local-real-demo` overlay: Neo4j, Relix (port 8084), Syntology, local NetworkPolicies, and the vLLM extraction ingress exception.

Activation flags remain **false** in the committed manifests. Local Compose
proves the behavior; it is not K8s activation evidence.

## What is blocked

Only the live K8s run remains:

- **#589** — K8s activation gate and post-#592 rerun.
- **#599** — live Entity Graph E2E against the deployed stack.

The cluster is physically unreachable from this machine:

```text
dial tcp 192.168.10.30:6443: connect: no route to host
```

Do not treat this as a code or manifest failure. There is no code workaround for
the missing route.

## Flag consistency check (2026-11-11)

The activation/env names were checked across Java config, Docker Compose, K8s
manifests, and the runbook:

```bash
git grep -n -E 'EXTRACTION_CONSUMER_ENABLED|EXTRACTION_GRAPH_ENABLED|RESOLUTION_CONSUMER_ENABLED|PROMOTION_CONSUMER_ENABLED|RECONCILIATION_ENABLED|PROMOTION_PRODUCER_ENABLED' \
  -- '*.java' '*.yaml' '*.yml' '*.md'
```

Result: all names resolve to the same spellings; no mismatched flag names were
found. `PROMOTION_PRODUCER_ENABLED` is the #583 producer switch added in #601.

## When the cluster returns

### 1. Pre-flight

```bash
kubectl get nodes
cd deployments/homelab
IMAGE_TAG=local-real-demo ./scripts/mirror-images.sh
```

Confirm vLLM extraction, TEI embedding, and the registry are reachable. Populate
`config/.env` with real `SYNANTON_JWT_SECRET`, `REGISTRY_*`, `POSTGRES_PASSWORD`,
and `NEO4J_PASSWORD` values.

### 2. Pin and deploy

Build/push at the activation head, then replace the mutable
`:local-real-demo` tags in `k8s/local-real-demo/{synflux,synquest,relix,syntology,extraction-gateway}.yaml`
with the resulting digests. Record the digests.

```bash
./scripts/deploy-demo.sh
```

This creates `synanton-jwt`, `postgres-cred`, and `neo4j-cred`, applies the data
layer and overlay, then applies the TEI/vLLM gpu-plane policies. Confirm:

```bash
kubectl -n synanton get statefulset/neo4j deployment/relix deployment/syntology
```

### 3. Enable the gated consumers

Use one recorded command set. The names are identical in the application YAML,
Compose, K8s ConfigMaps/deployments, and runbook; the flag sweep found no
mismatches.

```bash
kubectl -n synanton set env deploy/synflux \
  EXTRACTION_PROVIDER=llm \
  EXTRACTION_CONSUMER_ENABLED=true \
  EXTRACTION_GRAPH_ENABLED=true \
  PROMOTION_PRODUCER_ENABLED=true
kubectl -n synanton set env deploy/synquest RESOLUTION_CONSUMER_ENABLED=true
kubectl -n synanton set env deploy/relix PROMOTION_CONSUMER_ENABLED=true
```

Enable `RECONCILIATION_ENABLED=true` only for the #589 reconciliation evidence
run, not by default.

### 4. Seed and project canonical entities

The graph writer does not create canonical `:Entity` nodes. Seed the fixture and
project it to Synquest before uploading the E2E document:

```bash
kubectl -n synanton exec -i neo4j-0 -- sh -c \
  'cypher-shell -u neo4j -p "${NEO4J_AUTH#*/}"' \
  < demo-data/rak-halcyon-entities.cypher

kubectl -n synanton port-forward svc/neo4j 17474:7474 >/tmp/neo4j-pf.log 2>&1 &
kubectl -n synanton port-forward svc/synquest 18083:8083 >/tmp/synquest-pf.log 2>&1 &
NEO4J_HTTP_URL=http://127.0.0.1:17474 NEO4J_DATABASE=neo4j \
  NEO4J_USER=neo4j NEO4J_PASSWORD="$NEO4J_PASSWORD" \
  SYNQUEST_URL=http://127.0.0.1:18083 \
  SYNANTON_JWT_SECRET="$SYNANTON_JWT_SECRET" TENANT=demo \
  python3 tools/entity-projection/project_entities.py
```

Require nine projected entities.

### 5. Run the gate and live E2E

If #592 is not merged, record that the evidence applies to the current
transitional Neo4j seam. If #592 merged before activation, run everything on the
post-#592 tree. If it merges after, rerun before closing #589.

Run the #599 live spec against the deployed UI and Synflux:

```bash
kubectl -n synanton port-forward svc/synflux 18090:8090 >/tmp/synflux-pf.log 2>&1 &
cd test/e2e
BASE_URL=http://<worker-node>:30080 \
  SYNFLUX_URL=http://127.0.0.1:18090 \
  LOCAL_REAL_DEMO_TENANT=demo \
  SYNANTON_JWT_SECRET="$SYNANTON_JWT_SECRET" \
  npm run test:entity-graph-live
```

Record the Playwright report, status JSON, Entity Graph screenshot, Neo4j/Cassandra
checks, flag values, and image digests in `docs/acceptance/k8s-activation-YYYY-MM-DD.md`
and link them from #589/#599.

### 6. Rollback if activation fails

Disable background work without deleting data:

```bash
kubectl -n synanton set env deploy/synflux \
  EXTRACTION_CONSUMER_ENABLED=false EXTRACTION_GRAPH_ENABLED=false PROMOTION_PRODUCER_ENABLED=false
kubectl -n synanton set env deploy/synquest RESOLUTION_CONSUMER_ENABLED=false
kubectl -n synanton set env deploy/relix \
  PROMOTION_CONSUMER_ENABLED=false RECONCILIATION_ENABLED=false
```

Keep PVCs and evidence. Roll back the image revision if needed.

## If the live E2E fails on K8s

Check these four differences from Compose first:

1. **NetworkPolicies** — gpu-plane default-deny; confirm the TEI and vLLM
   exceptions, and that Relix/Neo4j/Syntology admit the expected callers.
2. **Images actually pulled by the nodes** — verify digests in the Deployment
   specs, registry auth, and `imagePullPolicy`; a stale mutable tag is not
   evidence.
3. **Secret propagation** — `synanton-jwt` in `synanton`, `postgres-cred`,
   `neo4j-cred`, and any identity material needed by gpu-plane. Missing secrets
   fail closed.
4. **PVC binding** — Neo4j and Syntology TDB2 claims must bind before the pods
   become ready; check Longhorn and the Syntology DB-init container.

Then inspect the exact source status:

```bash
curl -fsS -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:18090/v1/sources/<source-id>/status | jq
```

Require `extraction_status=KNOWLEDGE_READY`, `graph_status=GRAPH_COMMITTED`,
and `resolution_status=COMPLETED` before debugging the UI.

## What not to do

- Do not change the resolution policy or thresholds; the fixture and live
  Compose E2E are green.
- Do not change extraction prompts or provider behavior; that invalidates #593
  evidence.
- Do not start #556 adaptive RRF, new connectors, agent features, or UI work.
- Do not work around the missing cluster route with code changes.
- Do not enable flags without recording evidence and the exact tree.

## Definition of done

#589 closes only when the live upload → extraction → graph commit → resolution →
promotion → Entity Graph → evidence journey passes on the recorded tree, the
#592 rerun rule is satisfied, and the acceptance record is linked. Green Compose
tests alone are not activation.

## References

- `docs/planning/k8s-activation-runbook.md`
- `deployments/homelab/k8s/local-real-demo/`
- PR #601, #604, #605, #606
- Issues #589, #592, #599
