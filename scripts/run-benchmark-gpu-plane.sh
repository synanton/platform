#!/usr/bin/env bash
# Start (or stop) the retrieval-benchmark stack with embeddings from the GPU plane.
# The stack is Cassandra, MinIO, synvault, synflux and synquest; synflux and synquest run
# the gpu-plane profile, joined to the GPU Gateway's network over mTLS as the
# `synanton-benchmark` principal. Retrieval benchmark plan §6 Phase B1-G (G5).
#
#   scripts/run-benchmark-gpu-plane.sh up     [EMBED_MODEL=synanton-free-embedding]
#   scripts/run-benchmark-gpu-plane.sh down   # stop synflux + synquest (data volumes kept)
#
# The GPU Gateway is NOT started here. It lives in the sibling gpu-runtime repo and holds the
# provider key. Start it there first (deployments/external: gen-certs.sh, then docker compose
# up -d with its .env; see tools/gpu7-check/README.md).
#
# TLS: the gateway's dev PKI keys are 0600 and owned by the host user, but the platform
# containers run as a different uid. A copy of the benchmark client cert/key (dev,
# self-signed, never committed) goes into tools/retrieval-eval/.cache/gpu-plane-tls/ (git-ignored)
# and is mounted read-only. Production certificates come from the platform CA (GPU-6).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
D="$REPO_ROOT/deployment/docker"
COMPOSE=(docker compose -f "$D/compose.yaml" -f "$D/compose.gpu-plane.yaml")
PKI="${GPU_PLANE_PKI_DIR:-$REPO_ROOT/../gpu-runtime/deployments/external/certs}"
CLIENT="${GPU_PLANE_CLIENT:-synanton-benchmark}"
export GPU_PLANE_TLS_DIR="${GPU_PLANE_TLS_DIR:-$REPO_ROOT/tools/retrieval-eval/.cache/gpu-plane-tls}"
export GPU_PLANE_NETWORK="${GPU_PLANE_NETWORK:-gpu-7-external_default}"

prepare_tls() {
  for f in ca.crt "$CLIENT.crt" "$CLIENT.key"; do
    [[ -r "$PKI/$f" ]] || { echo "missing $PKI/$f — run gpu-runtime deployments/external/scripts/gen-certs.sh" >&2; exit 1; }
  done
  mkdir -p "$GPU_PLANE_TLS_DIR"
  cp "$PKI/ca.crt" "$GPU_PLANE_TLS_DIR/ca.crt"
  cp "$PKI/$CLIENT.crt" "$GPU_PLANE_TLS_DIR/client.crt"
  cp "$PKI/$CLIENT.key" "$GPU_PLANE_TLS_DIR/client.key"
  chmod 644 "$GPU_PLANE_TLS_DIR"/*   # dev PKI only: readable by the container's non-root user
  echo "TLS: $CLIENT client certificate → $GPU_PLANE_TLS_DIR"
}

wait_healthy() {
  local svc="$1"
  for i in $(seq 1 40); do
    status=$("${COMPOSE[@]}" ps "$svc" --format '{{.Health}}' 2>/dev/null || true)
    [[ "$status" == "healthy" ]] && { echo "  $svc healthy"; return 0; }
    sleep 5
  done
  echo "$svc did not become healthy" >&2
  "${COMPOSE[@]}" logs "$svc" --tail=60 >&2
  exit 1
}

case "${1:-up}" in
  up)
    docker network inspect "$GPU_PLANE_NETWORK" >/dev/null 2>&1 || {
      echo "network $GPU_PLANE_NETWORK not found — start the GPU Gateway (gpu-runtime deployments/external) first" >&2; exit 1; }
    prepare_tls
    echo "EMBED_MODEL=${EMBED_MODEL:-synanton-free-embedding} EMBED_DIM=${EMBED_DIM:-1024} EMBED_TRUNCATE_DIM=${EMBED_TRUNCATE_DIM:-1024}"
    for svc in synvault synflux synquest; do "${COMPOSE[@]}" build "$svc"; done
    "${COMPOSE[@]}" up -d cassandra minio minio-init synvault synflux synquest
    wait_healthy synflux
    wait_healthy synquest
    echo "ready: synflux :8090, synquest :8083 (gpu-plane profile)"
    ;;
  down)
    "${COMPOSE[@]}" stop synflux synquest
    ;;
  *)
    echo "usage: $0 up|down" >&2; exit 2 ;;
esac
