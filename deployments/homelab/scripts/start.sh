#!/usr/bin/env bash
# start.sh — resume the demo slice after scripts/stop.sh.
#
# Preflight is explicit: required workloads must exist, and only labelled
# Synanton workers are uncordoned. The script waits for readiness, not just
# for scale requests to be accepted.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
SYNFLUX_REPLICAS="${SYNFLUX_REPLICAS:-2}"

EXTRACTION_GATEWAY_MANAGED="${EXTRACTION_GATEWAY_MANAGED:-1}"
EXTRACTION_GATEWAY_HEALTH_URL="${EXTRACTION_GATEWAY_HEALTH_URL:-}"
REQUIRED_DEPLOYMENTS=(security ui synflux synquest minio relix syntology)
REQUIRED_STATEFULSETS=(cassandra postgres neo4j)
OPTIONAL_DEPLOYMENTS=(platform)

log() { printf '\033[1;34m[start]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[start]\033[0m %s\n' "$*" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl not found"
kubectl cluster-info >/dev/null 2>&1 || die "cluster unreachable"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace $NAMESPACE missing — run deploy-demo.sh first"

for d in "${REQUIRED_DEPLOYMENTS[@]}"; do
    kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1 \
        || die "required deployment missing: $d"
done
if [ "$EXTRACTION_GATEWAY_MANAGED" = "1" ]; then
    kubectl -n "$NAMESPACE" get deployment extraction-gateway >/dev/null 2>&1 \
        || die "required content extractor deployment missing: extraction-gateway"
else
    [ -n "$EXTRACTION_GATEWAY_HEALTH_URL" ] \
        || die "EXTRACTION_GATEWAY_MANAGED=0 requires EXTRACTION_GATEWAY_HEALTH_URL"
    log "content extractor is externally managed; readiness will be checked, not owned"
fi
for s in "${REQUIRED_STATEFULSETS[@]}"; do
    kubectl -n "$NAMESPACE" get statefulset "$s" >/dev/null 2>&1 \
        || die "required statefulset missing: $s"
done

log "uncordoning labelled Synanton workers only"
mapfile -t workers < <(kubectl get nodes -l synanton.io/role -o name)
[ "${#workers[@]}" -gt 0 ] || die "no nodes carry synanton.io/role"
kubectl uncordon "${workers[@]}"

check_extraction_gateway_health() {
    local response
    log "checking content extractor readiness at :8092/actuator/health"
    if [ "$EXTRACTION_GATEWAY_MANAGED" = "1" ]; then
        kubectl -n "$NAMESPACE" rollout status deployment/extraction-gateway --timeout=300s
        response="$(kubectl -n "$NAMESPACE" exec deployment/extraction-gateway -- \
            wget -qO- http://127.0.0.1:8092/actuator/health 2>/dev/null || true)"
    else
        command -v curl >/dev/null || die "curl not found for external extractor health check"
        response="$(curl -fsS --max-time 5 "$EXTRACTION_GATEWAY_HEALTH_URL" || true)"
    fi
    printf '%s' "$response" | grep -q '"status":"UP"' \
        || die "content extractor is not ready (HTTP /actuator/health did not report UP)"
}

log "scaling database dependencies"
for s in "${REQUIRED_STATEFULSETS[@]}"; do
    replicas=1
    [ "$s" = "cassandra" ] && replicas=3
    kubectl -n "$NAMESPACE" scale statefulset/"$s" --replicas="$replicas"
done

if [ "$EXTRACTION_GATEWAY_MANAGED" = "1" ]; then
    log "scaling content extractor dependency before extraction-dependent workloads"
    kubectl -n "$NAMESPACE" scale deployment/extraction-gateway --replicas=1
fi
check_extraction_gateway_health

log "scaling managed workloads"
for d in "${REQUIRED_DEPLOYMENTS[@]}"; do
    replicas=1
    [ "$d" = "synflux" ] && replicas="$SYNFLUX_REPLICAS"
    kubectl -n "$NAMESPACE" scale deployment/"$d" --replicas="$replicas"
done
for d in "${OPTIONAL_DEPLOYMENTS[@]}"; do
    if ! kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1; then
        log "optional deployment absent: $d (not counted as success)"
    elif [ "${START_PLATFORM:-0}" = "1" ]; then
        kubectl -n "$NAMESPACE" scale deployment/"$d" --replicas=1
    else
        log "optional deployment skipped: $d (set START_PLATFORM=1 to start)"
    fi
done
log "waiting for required readiness"
kubectl -n "$NAMESPACE" rollout status statefulset/cassandra --timeout=600s
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s
kubectl -n "$NAMESPACE" rollout status statefulset/neo4j --timeout=300s
for d in "${REQUIRED_DEPLOYMENTS[@]}"; do
    kubectl -n "$NAMESPACE" rollout status deployment/"$d" --timeout=300s
done
check_extraction_gateway_health

log "started — run scripts/verify-demo.sh for the deployment health report"
