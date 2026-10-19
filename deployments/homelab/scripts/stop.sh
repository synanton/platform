#!/usr/bin/env bash
# stop.sh — scale the slice to zero WITHOUT cordoning nodes.
#
# Cordoning is cluster-wide and parks the gpu-plane / speech workloads too;
# only the cluster-level stop (gpu-runtime's cluster-stop.sh) may cordon.
# PVCs, PVs, and secrets are kept.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"

log() { printf '\033[1;34m[stop]\033[0m %s\n' "$*"; }

log "scaling the slice to zero (PVCs retained, nodes stay schedulable)"
for d in synflux synquest extraction-gateway minio; do
    kubectl -n "$NAMESPACE" scale deployment/"$d" --replicas=0 2>/dev/null || true
done
for s in cassandra postgres; do
    kubectl -n "$NAMESPACE" scale statefulset/"$s" --replicas=0 2>/dev/null || true
done
log "stopped — run scripts/start.sh to resume"
