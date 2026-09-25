#!/usr/bin/env bash
# Start (or stop) the retrieval-benchmark stack with embeddings from the GPU plane.
# The stack is Cassandra, MinIO, synvault, synflux and synquest; synflux and synquest run
# the gpu-plane profile (gRPC synanton.gpu.v1, mTLS) as the `synanton-benchmark` principal.
#
#   scripts/run-benchmark-gpu-plane.sh up   [--plane gpu5|gpu7]   (default gpu5)
#   scripts/run-benchmark-gpu-plane.sh down                       # stop synflux + synquest (data kept)
#
# Planes (retrieval benchmark plan §6):
#   gpu5  GPU-5, homelab k8s (Phase B1-K), via NodePort gpu-gateway-external on node1:
#         GPU_PLANE_ENDPOINT=192.168.10.31:30990, GPU_TLS_AUTHORITY=gpu-gateway,
#         PKI gpu-runtime/git-ignored/gpu5-pki (client cert: gpu-runtime
#         deployments/homelab/scripts/issue-client-cert.sh synanton-benchmark),
#         EMBED_MODEL=synanton-bge-base-embedding, EMBED_DIM=768, EMBED_TRUNCATE_DIM=0,
#         GPU_PLANE_MAX_RPM=0 (no provider rate limit on the home cluster).
#   gpu7  GPU-7 compose stack on this host (Phase B1-G): joins its Docker network
#         (compose.gpu7-network.yaml), endpoint gateway:9090, PKI
#         gpu-runtime/deployments/external/certs, synanton-free-embedding at 1024 (truncated),
#         15 req/min pacing.
# Any variable above can be overridden from the environment. The GPU Gateway itself is not
# started here; it lives in the sibling gpu-runtime repo.
#
# TLS: the dev PKI keys are 0600 and owned by the host user, but the platform containers run
# as a different uid. A copy of the client cert/key (dev, self-signed, never committed) goes
# into tools/retrieval-eval/.cache/gpu-plane-tls/ (git-ignored) and is mounted read-only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
RUNTIME="$REPO_ROOT/../gpu-runtime"
D="$REPO_ROOT/deployment/docker"

CMD="${1:-up}"; shift || true
PLANE="gpu5"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --plane) PLANE="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

COMPOSE=(docker compose -f "$D/compose.yaml" -f "$D/compose.gpu-plane.yaml")
case "$PLANE" in
  gpu5)
    : "${GPU_PLANE_PKI_DIR:=$RUNTIME/git-ignored/gpu5-pki}"
    export GPU_PLANE_ENDPOINT="${GPU_PLANE_ENDPOINT:-192.168.10.31:30990}"
    export GPU_TLS_AUTHORITY="${GPU_TLS_AUTHORITY:-gpu-gateway}"
    export EMBED_MODEL="${EMBED_MODEL:-synanton-bge-base-embedding}"
    export EMBED_DIM="${EMBED_DIM:-768}"
    export EMBED_TRUNCATE_DIM="${EMBED_TRUNCATE_DIM:-0}"
    export GPU_PLANE_MAX_RPM="${GPU_PLANE_MAX_RPM:-0}"
    ;;
  gpu7)
    : "${GPU_PLANE_PKI_DIR:=$RUNTIME/deployments/external/certs}"
    export GPU_PLANE_ENDPOINT="${GPU_PLANE_ENDPOINT:-gateway:9090}"
    export GPU_TLS_AUTHORITY="${GPU_TLS_AUTHORITY:-}"
    export EMBED_MODEL="${EMBED_MODEL:-synanton-free-embedding}"
    export EMBED_DIM="${EMBED_DIM:-1024}"
    export EMBED_TRUNCATE_DIM="${EMBED_TRUNCATE_DIM:-1024}"
    export GPU_PLANE_MAX_RPM="${GPU_PLANE_MAX_RPM:-15}"
    export GPU_PLANE_NETWORK="${GPU_PLANE_NETWORK:-gpu-7-external_default}"
    COMPOSE+=(-f "$D/compose.gpu7-network.yaml")
    ;;
  *) echo "unknown plane: $PLANE (gpu5 | gpu7)" >&2; exit 2 ;;
esac
CLIENT="${GPU_PLANE_CLIENT:-synanton-benchmark}"
export GPU_PLANE_TLS_DIR="${GPU_PLANE_TLS_DIR:-$REPO_ROOT/tools/retrieval-eval/.cache/gpu-plane-tls}"

prepare_tls() {
  for f in ca.crt "$CLIENT.crt" "$CLIENT.key"; do
    [[ -r "$GPU_PLANE_PKI_DIR/$f" ]] || { echo "missing $GPU_PLANE_PKI_DIR/$f" >&2; exit 1; }
  done
  mkdir -p "$GPU_PLANE_TLS_DIR"
  cp "$GPU_PLANE_PKI_DIR/ca.crt" "$GPU_PLANE_TLS_DIR/ca.crt"
  cp "$GPU_PLANE_PKI_DIR/$CLIENT.crt" "$GPU_PLANE_TLS_DIR/client.crt"
  cp "$GPU_PLANE_PKI_DIR/$CLIENT.key" "$GPU_PLANE_TLS_DIR/client.key"
  chmod 644 "$GPU_PLANE_TLS_DIR"/*   # dev PKI only: readable by the container's non-root user
  echo "TLS: $CLIENT client certificate from $GPU_PLANE_PKI_DIR → $GPU_PLANE_TLS_DIR"
}

wait_healthy() {
  local svc="$1"
  for _ in $(seq 1 40); do
    status=$("${COMPOSE[@]}" ps "$svc" --format '{{.Health}}' 2>/dev/null || true)
    [[ "$status" == "healthy" ]] && { echo "  $svc healthy"; return 0; }
    sleep 5
  done
  echo "$svc did not become healthy" >&2
  "${COMPOSE[@]}" logs "$svc" --tail=60 >&2
  exit 1
}

case "$CMD" in
  up)
    if [[ "$PLANE" == gpu7 ]]; then
      docker network inspect "$GPU_PLANE_NETWORK" >/dev/null 2>&1 || {
        echo "network $GPU_PLANE_NETWORK not found — start the GPU-7 Gateway (gpu-runtime deployments/external) first" >&2; exit 1; }
    else
      host="${GPU_PLANE_ENDPOINT%:*}"; port="${GPU_PLANE_ENDPOINT##*:}"
      timeout 5 bash -c "</dev/tcp/$host/$port" 2>/dev/null || {
        echo "cannot reach the GPU-5 Gateway at $GPU_PLANE_ENDPOINT (NodePort gpu-gateway-external; NetworkPolicy allow-list)" >&2; exit 1; }
    fi
    prepare_tls
    echo "plane=$PLANE endpoint=$GPU_PLANE_ENDPOINT authority=${GPU_TLS_AUTHORITY:-<none>} model=$EMBED_MODEL dim=$EMBED_DIM truncate=$EMBED_TRUNCATE_DIM rpm=$GPU_PLANE_MAX_RPM"
    for svc in synvault synflux synquest; do "${COMPOSE[@]}" build "$svc"; done
    "${COMPOSE[@]}" up -d cassandra minio minio-init synvault synflux synquest
    wait_healthy synflux
    wait_healthy synquest
    echo "ready: synflux :8090, synquest :8083 (gpu-plane profile, $PLANE)"
    ;;
  down)
    "${COMPOSE[@]}" stop synflux synquest
    ;;
  *)
    echo "usage: $0 up|down [--plane gpu5|gpu7]" >&2; exit 2 ;;
esac
