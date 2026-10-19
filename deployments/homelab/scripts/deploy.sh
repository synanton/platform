#!/usr/bin/env bash
# deploy.sh — the platform slice on the homelab cluster.
#
# Order is deliberate (data before apps) and every secret comes from
# config/.env — nothing is committed with a value. Ends with the anti-affinity
# assertion: the one check that prevents a false-positive P1 run.
#
# Usage: IMAGE_TAG=slice-YYYYMMDD ./deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
IMAGE_TAG="${IMAGE_TAG:-}"
ENV_FILE="${ENV_FILE:-$ROOT/config/.env}"

log() { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }
subst() { sed "s/PLACEHOLDER_TAG/${IMAGE_TAG}/g"; }

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
[ -n "$IMAGE_TAG" ] || die "IMAGE_TAG is required (e.g. IMAGE_TAG=slice-20261018 ./deploy.sh)"

# ── Namespace + secrets ──────────────────────────────────────────────────────
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

log "registry pull secret"
"$SCRIPT_DIR/create-registry-secret.sh"

# ── Storage ──────────────────────────────────────────────────────────────────
log "static PVs (local-ssd, Retain) + shared fixtures (NFS)"
kubectl apply -f "$ROOT/k8s/storage/local-ssd-pvs.yaml"
kubectl apply -f "$ROOT/k8s/storage/slice-fixtures.yaml"

# ── Data layer ───────────────────────────────────────────────────────────────
log "postgres"
kubectl apply -f "$ROOT/k8s/postgres/"
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s

log "cassandra (RF=3, node1/2/3)"
kubectl apply -f "$ROOT/k8s/cassandra/"
# Ring convergence takes minutes after rollout status returns.
kubectl -n "$NAMESPACE" rollout status statefulset/cassandra --timeout=600s

log "keyspace (NetworkTopologyStrategy RF=3 — pre-created so the service's RF=1 migration cannot win)"
cqlsh_on_cassandra() {
    kubectl -n "$NAMESPACE" exec cassandra-0 -- cqlsh -e "$1"
}
until cqlsh_on_cassandra "SELECT now() FROM system.local" >/dev/null 2>&1; do sleep 5; done
cqlsh_on_cassandra "$(cat "$ROOT/config/cassandra-keyspace.cql")"

log "minio + bucket init"
kubectl apply -f "$ROOT/k8s/minio/"
kubectl -n "$NAMESPACE" rollout status deployment/minio --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=complete job/minio-init --timeout=180s

# ── Application layer ────────────────────────────────────────────────────────
log "extraction gateway"
subst < "$ROOT/k8s/extraction-gateway/extraction-gateway.yaml" | kubectl -n "$NAMESPACE" apply -f -
kubectl -n "$NAMESPACE" rollout status deployment/extraction-gateway --timeout=180s

log "search (synquest)"
subst < "$ROOT/k8s/search/synquest.yaml" | kubectl -n "$NAMESPACE" apply -f -
kubectl -n "$NAMESPACE" rollout status deployment/synquest --timeout=180s

log "synflux (2 replicas, required anti-affinity)"
subst < "$ROOT/k8s/synflux/synflux.yaml" | kubectl -n "$NAMESPACE" apply -f -
kubectl -n "$NAMESPACE" rollout status deployment/synflux --timeout=300s

# ── The assertion that makes the P1 run valid ────────────────────────────────
nodes=$(kubectl -n "$NAMESPACE" get pods -l app=synflux --field-selector=status.phase=Running \
    -o jsonpath='{.items[*].spec.nodeName}' | tr ' ' '\n' | sort -u | grep -c . || true)
log "synflux replicas span ${nodes} node(s)"
[ "$nodes" = "2" ] || die "synflux replicas are not on two distinct nodes — the P1 lease-loss and set-race tests would be invalid (check the compute labels and anti-affinity)"

# ── Optional: Kafka profile (outbox-only is the default) ─────────────────────
# kubectl apply -f "$ROOT/k8s/kafka/"
# kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout=300s

log "done — pods:"
kubectl -n "$NAMESPACE" get pods -o wide
