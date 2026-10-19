#!/usr/bin/env bash
# start.sh — resume the slice after the cluster was stopped.
#
# Uncordons the workers (gpu-runtime's cluster-stop.sh parks them) and scales
# the slice deployments back up. Namespace-scoped on purpose: cordoning is
# cluster-wide and belongs to whoever stops the whole cluster.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"

log() { printf '\033[1;34m[start]\033[0m %s\n' "$*"; }

log "uncordoning workers"
for n in node1 node2 node3; do kubectl uncordon "$n"; done

log "scaling the slice back up"
kubectl -n "$NAMESPACE" scale statefulset/cassandra --replicas=3
kubectl -n "$NAMESPACE" scale statefulset/postgres --replicas=1
kubectl -n "$NAMESPACE" scale deployment/minio --replicas=1
kubectl -n "$NAMESPACE" scale deployment/extraction-gateway --replicas=1
kubectl -n "$NAMESPACE" scale deployment/synquest --replicas=1
kubectl -n "$NAMESPACE" scale deployment/synflux --replicas="${SYNFLUX_REPLICAS:-2}"

log "waiting for rollouts"
kubectl -n "$NAMESPACE" rollout status statefulset/cassandra --timeout=600s
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s
kubectl -n "$NAMESPACE" rollout status deployment/synflux --timeout=300s
kubectl -n "$NAMESPACE" rollout status deployment/synquest --timeout=180s
log "started"
