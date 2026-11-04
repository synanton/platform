#!/usr/bin/env bash
# deploy-demo.sh — deploy the #399 local-real-demo (data layer + demo overlay).
#
# deploy.sh deploys the #253 base slice; this deploys the demo the checkpoint-1
# runbook verifies. The two are different stacks (different images, tenant demo-v2,
# a security/UI layer) and must not be layered on top of each other.
#
# Idempotent: secrets/keyspace/bucket use create-or-refresh / IF NOT EXISTS /
# --ignore-existing, so re-running after a cordon or partial deploy resumes cleanly.
#
# Usage: ./scripts/deploy-demo.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
ENV_FILE="${ENV_FILE:-$ROOT/config/.env}"

log() { printf '\033[1;34m[deploy-demo]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[deploy-demo]\033[0m %s\n' "$*" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl not found"
kubectl cluster-info >/dev/null 2>&1 || die "cluster unreachable"
[ -f "$ENV_FILE" ] || die "missing $ENV_FILE (copy config/.env.example)"
# shellcheck disable=SC1091
. "$ENV_FILE"
: "${SYNANTON_JWT_SECRET:?set SYNANTON_JWT_SECRET in $ENV_FILE (>= 32 bytes)}"
: "${REGISTRY_USER:?set REGISTRY_USER in $ENV_FILE}"
: "${REGISTRY_PASSWORD:?set REGISTRY_PASSWORD in $ENV_FILE}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-changeme}"
MINIO_USER="${MINIO_USER:-minioadmin}"
MINIO_PASSWORD="${MINIO_PASSWORD:-minioadmin}"

# ── Namespace + secrets (apply refreshes to match config/.env) ──────────────
log "namespace"
kubectl apply -f "$ROOT/k8s/namespace.yaml"

log "secrets (from config/.env)"
kubectl -n "$NAMESPACE" create secret generic synanton-jwt \
    --from-literal=secret="$SYNANTON_JWT_SECRET" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create secret generic postgres-cred \
    --from-literal=password="$POSTGRES_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create secret generic minio-cred \
    --from-literal=user="$MINIO_USER" --from-literal=password="$MINIO_PASSWORD" \
    --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create secret generic minio-cred-env \
    --from-literal=MC_HOST_local="http://$MINIO_USER:$MINIO_PASSWORD@minio:9000" \
    --dry-run=client -o yaml | kubectl apply -f -

log "registry pull secret"
"$SCRIPT_DIR/create-registry-secret.sh"

# ── Storage: local-ssd PVs for the data layer (slice-fixtures is #253-only) ──
log "static PVs (local-ssd, Retain)"
kubectl apply -f "$ROOT/k8s/storage/local-ssd-pvs.yaml"

# ── Data layer ──────────────────────────────────────────────────────────────
log "postgres"
kubectl apply -f "$ROOT/k8s/postgres/"
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s

log "cassandra (RF=3, node1/2/3)"
kubectl apply -f "$ROOT/k8s/cassandra/"
kubectl -n "$NAMESPACE" rollout status statefulset/cassandra --timeout=600s

log "keyspace (NetworkTopologyStrategy RF=3 — idempotent)"
until kubectl -n "$NAMESPACE" exec cassandra-0 -- cqlsh -e "SELECT now() FROM system.local" >/dev/null 2>&1; do
    sleep 5
done
kubectl -n "$NAMESPACE" exec cassandra-0 -- cqlsh -e "$(cat "$ROOT/config/cassandra-keyspace.cql")"

log "waiting for cassandra schema agreement"
agreed=0
for _ in {1..30}; do
    local_v=$(kubectl -n "$NAMESPACE" exec cassandra-0 -- \
        cqlsh -e "SELECT schema_version FROM system.local" 2>/dev/null \
        | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1 || true)
    peers_v=$(kubectl -n "$NAMESPACE" exec cassandra-0 -- \
        cqlsh -e "SELECT schema_version FROM system.peers" 2>/dev/null \
        | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | sort -u || true)
    if [ -n "$local_v" ] && [ "$(printf '%s\n%s\n' "$local_v" "$peers_v" | sort -u | wc -l)" = "1" ]; then
        agreed=1
        log "schema agreement reached"
        break
    fi
    sleep 5
done
[ "$agreed" = "1" ] || die "cassandra schema did not reach agreement within 150s"

log "minio + bucket init"
kubectl -n "$NAMESPACE" delete job/minio-init --ignore-not-found=true >/dev/null 2>&1 || true
kubectl apply -f "$ROOT/k8s/minio/"
kubectl -n "$NAMESPACE" rollout status deployment/minio --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=complete job/minio-init --timeout=180s

# ── Demo overlay ────────────────────────────────────────────────────────────
log "local-real-demo overlay"
kubectl apply -k "$ROOT/k8s/local-real-demo/"

log "done — pods:"
kubectl -n "$NAMESPACE" get pods -o wide
