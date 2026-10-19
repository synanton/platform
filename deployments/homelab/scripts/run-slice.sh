#!/usr/bin/env bash
# run-slice.sh — bridge the cluster to tests/integration/vertical-slice.sh.
#
# Port-forwards synflux/synquest, points the acceptance script at them, and
# points the fixture root at the shared NFS volume (the operator's mount of
# node1's export). The script writes its per-run fixture root there; the pods
# see it at /fixtures.
#
# Preconditions: deploy.sh completed; the operator's machine has the node1
# NFS export mounted (as-built: /mnt/wd6000). Override FIXTURE_NFS_MOUNT if
# it differs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"       # platform repo root
NAMESPACE="${NAMESPACE:-synanton}"
FIXTURE_NFS_MOUNT="${FIXTURE_NFS_MOUNT:-/mnt/wd6000}"
FIXTURE_NFS_SUBDIR="${FIXTURE_NFS_SUBDIR:-synanton-slice}"
LOCAL_SYNFLUX_PORT="${LOCAL_SYNFLUX_PORT:-18090}"
LOCAL_SYNQUEST_PORT="${LOCAL_SYNQUEST_PORT:-18083}"

log() { printf '\033[1;34m[run-slice]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[run-slice]\033[0m %s\n' "$*" >&2; exit 1; }

FIXTURE_HOST_DIR="$FIXTURE_NFS_MOUNT/$FIXTURE_NFS_SUBDIR"
[ -d "$FIXTURE_HOST_DIR" ] || die "fixture mount not found: $FIXTURE_HOST_DIR (mount node1:/mnt/WD6000/nfs_share, or set FIXTURE_NFS_MOUNT)"
[ -f "$ROOT/.env" ] || die "missing $ROOT/.env (the acceptance script sources a default JWT secret from it)"
# The cluster verifies with the secret in config/.env — pass it explicitly;
# the repo .env carries the compose dev secret.
HOMELAB_ENV="$ROOT/deployments/homelab/config/.env"
[ -f "$HOMELAB_ENV" ] || die "missing $HOMELAB_ENV"
# shellcheck disable=SC1090
. "$HOMELAB_ENV"
: "${SYNANTON_JWT_SECRET:?set SYNANTON_JWT_SECRET in $HOMELAB_ENV}"
export SYNANTON_JWT_SECRET

# Stable NodePort endpoints (the fault-injection restarts replace pods; a
# port-forward binds to one pod and dies). The services expose 30090/30083.
NODE_IP="${NODE_IP:-192.168.10.30}"
SYNFLUX_URL="http://$NODE_IP:30090"
SYNQUEST_URL="http://$NODE_IP:30083"
LOCAL_SYNFLUX_PORT=30090
LOCAL_SYNQUEST_PORT=30083

sleep 1
curl -sf "$SYNFLUX_URL/health" >/dev/null || die "synflux not reachable at $SYNFLUX_URL"
curl -sf "$SYNQUEST_URL/health" >/dev/null || die "synquest not reachable at $SYNQUEST_URL"

log "synflux:  $SYNFLUX_URL"
log "synquest: $SYNQUEST_URL"
log "fixtures: $FIXTURE_HOST_DIR (pods see /fixtures)"

WORKER_PROFILE=k8s \
K8S_NAMESPACE="$NAMESPACE" \
SYNFLUX_REPLICAS="${SYNFLUX_REPLICAS:-2}" \
SYNFLUX_URL="$SYNFLUX_URL" \
SYNQUEST_URL="$SYNQUEST_URL" \
FIXTURE_HOST_DIR="$FIXTURE_HOST_DIR" \
FIXTURE_ROOT="/fixtures" \
"$ROOT/tests/integration/vertical-slice.sh"
