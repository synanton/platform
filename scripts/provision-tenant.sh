#!/usr/bin/env bash
# Provision one isolated evaluation tenant (#728).
# Usage: scripts/provision-tenant.sh <eval-tenant> <regex|llm> [ontology-version] [ttl-path]
set -euo pipefail

TENANT="${1:?usage: provision-tenant.sh <eval-tenant> <regex|llm> [ontology-version] [ttl-path]}"
PROVIDER="${2:?usage: provision-tenant.sh <eval-tenant> <regex|llm> [ontology-version] [ttl-path]}"
ONTOLOGY_VERSION="${3:-1.0.0}"
TTL_PATH="${4:-deployments/docker/data/syntology-tdb2/demo/1.0.0/ontology.ttl}"
NAMESPACE="${NAMESPACE:-synanton}"
AUDIT_DIR="${AUDIT_DIR:-docs/acceptance/provisioning-audit}"
OPERATOR="${OPERATOR:-${USER:-unknown}}"
GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"

case "$TENANT" in
  eval-*) ;;
  *) echo "refusing tenant '$TENANT': evaluation tenants must start with eval-" >&2; exit 2 ;;
esac
[ -f "$TTL_PATH" ] || { echo "ontology TTL not found: $TTL_PATH" >&2; exit 2; }

SECRET="${SYNANTON_JWT_SECRET:-$(kubectl -n "$NAMESPACE" get secret synanton-jwt -o jsonpath='{.data.secret}' | base64 -d)}"
TOKEN="$(python3 - "$SECRET" "$TENANT" <<'PY'
import sys, json, hmac, hashlib, base64, time
secret, tenant = sys.argv[1], sys.argv[2]
def b64(raw): return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
h = b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
p = b64(json.dumps({"sub":"tenant-provisioner","tenant_id":tenant,
                    "roles":["provisioner"], "exp":int(time.time())+1800}).encode())
print(f"{h}.{p}." + b64(hmac.new(secret.encode(), f"{h}.{p}".encode(), hashlib.sha256).digest()))
PY
)"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
kubectl -n "$NAMESPACE" port-forward svc/cassandra 19042:9042 >/tmp/provision-cassandra-port.log 2>&1 & PIDS+=($!)
kubectl -n "$NAMESPACE" port-forward svc/syntology 18080:8080 >/tmp/provision-syntology-port.log 2>&1 & PIDS+=($!)
kubectl -n "$NAMESPACE" port-forward svc/relix 18084:8084 >/tmp/provision-relix-port.log 2>&1 & PIDS+=($!)

for _ in $(seq 1 30); do
  if curl -sf http://127.0.0.1:18080/health >/dev/null 2>&1 \
     && curl -sf http://127.0.0.1:18084/health >/dev/null 2>&1 \
     && bash -c 'cat < /dev/null > /dev/tcp/127.0.0.1/19042' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

mkdir -p "$AUDIT_DIR"
export PROVISION_TOKEN="$TOKEN"
./gradlew -q :java:relix:provisionEvalTenant --args="--tenant $TENANT --provider $PROVIDER --ontology-version $ONTOLOGY_VERSION --ontology-ttl $TTL_PATH --syntology-url http://127.0.0.1:18080 --relix-url http://127.0.0.1:18084 --cassandra-contact-points 127.0.0.1:19042 --audit-dir $AUDIT_DIR --operator $OPERATOR --git-sha $GIT_SHA"
