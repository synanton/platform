#!/usr/bin/env bash
# stop.sh — scale the managed slice to zero without cordoning nodes.
#
# Reverse dependency order; PVCs/PVs/secrets are retained. Missing workloads
# are an error so a partial stop cannot look successful.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
EXTRACTION_GATEWAY_MANAGED="${EXTRACTION_GATEWAY_MANAGED:-1}"

log() { printf '\033[1;34m[stop]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[stop]\033[0m %s\n' "$*" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl not found"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace $NAMESPACE missing — nothing to stop"

DEPLOYMENTS=(ui security syntology relix synflux synquest minio)
if [ "$EXTRACTION_GATEWAY_MANAGED" = "1" ]; then
    DEPLOYMENTS=(ui security syntology relix synflux synquest extraction-gateway minio)
fi
OPTIONAL_DEPLOYMENTS=(platform)
STATEFULSETS=(neo4j postgres cassandra)

for d in "${DEPLOYMENTS[@]}"; do
    kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1 \
        || die "managed deployment missing: $d"
done
if [ "$EXTRACTION_GATEWAY_MANAGED" = "0" ]; then
    log "content extractor is externally managed; leaving extraction-gateway untouched"
fi
for d in "${OPTIONAL_DEPLOYMENTS[@]}"; do
    kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1 \
        || log "optional deployment absent: $d (not counted as success)"
done
for s in "${STATEFULSETS[@]}"; do
    kubectl -n "$NAMESPACE" get statefulset "$s" >/dev/null 2>&1 \
        || die "managed statefulset missing: $s"
done

log "scaling managed workloads to zero (PVCs retained, nodes stay schedulable)"
for d in "${DEPLOYMENTS[@]}"; do
    kubectl -n "$NAMESPACE" scale deployment/"$d" --replicas=0
done
for d in "${OPTIONAL_DEPLOYMENTS[@]}"; do
    if kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1; then
        kubectl -n "$NAMESPACE" scale deployment/"$d" --replicas=0
    fi
done
for s in "${STATEFULSETS[@]}"; do
    kubectl -n "$NAMESPACE" scale statefulset/"$s" --replicas=0
done

log "stopped — run scripts/start.sh to resume"
