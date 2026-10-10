#!/usr/bin/env bash
# Swap node1 TEI workloads (base bge-base-768d <-> small bge-small-384d) with
# pre-flight guards. Lesson of 2026-10-10: never shuffle replicas while an
# orphaned GPU container or a stale plugin checkpoint exists — admission
# storms + leaked VRAM holders lock the GPU out from inside an
# invisible-to-kubectl state.
#
# Usage: ./scripts/bench-tei-swap.sh [base|small]
#   base  -> scale small to 0, base to 1 (768d on :30800)
#   small -> scale base to 0, small to 1 (384d on :30801)
# Run from a host with kubectl access (node0). Requires ssh to node1.
set -euo pipefail

TARGET="${1:-}"
if [[ "${TARGET}" != "base" && "${TARGET}" != "small" ]]; then
  echo "usage: $0 [base|small]" >&2
  exit 2
fi

echo "== pre-flight: VRAM holders on node1 (must be none)"
HOLDERS="$(ssh -o ConnectTimeout=10 node1 \
  "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null" | grep -vc '^$' || true)"
if [[ "${HOLDERS}" != "0" ]]; then
  echo "ABORT: ${HOLDERS} compute process(es) hold the GPU. Identify via:" >&2
  echo "  ssh node1 \"ps -o pid,ppid,cmd -p \$(nvidia-smi --query-compute-apps=pid --format=csv,noheader | tr '\n' ',' )\"" >&2
  echo "If no live pod owns them, they are orphaned containerd containers:" >&2
  echo "  sudo ctr -n k8s.io containers list | grep -i tei   (on node1)" >&2
  exit 1
fi
echo "  VRAM free"

echo "== pre-flight: node1 allocatable GPU (must be 1)"
ALLOC="$(kubectl -n kube-system get node node1 -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
if [[ "${ALLOC}" != "1" ]]; then
  echo "ABORT: allocatable nvidia.com/gpu=${ALLOC:-?}. Device plugin unhealthy;" >&2
  echo "see gpu-runtime troubleshooting.md (orphaned-GPU-container entry)." >&2
  exit 1
fi
echo "  allocatable: 1"

if [[ "${TARGET}" == "base" ]]; then
  DOWN=tei-embedding-small; UP=tei-embedding; PORT=30800; DIM=768
else
  DOWN=tei-embedding; UP=tei-embedding-small; PORT=30801; DIM=384
fi

echo "== scaling ${DOWN} -> 0"
kubectl -n gpu-plane scale "deploy/${DOWN}" --replicas=0 >/dev/null
echo "== waiting for pods to terminate"
kubectl -n gpu-plane wait --for=delete "pods -l app=${DOWN}" --timeout=120s 2>/dev/null \
  || kubectl -n gpu-plane delete "pods -l app=${DOWN}" --ignore-not-found=true >/dev/null

echo "== scaling ${UP} -> 1"
kubectl -n gpu-plane scale "deploy/${UP}" --replicas=1 >/dev/null
echo "== waiting for rollout"
kubectl -n gpu-plane rollout status "deployment/${UP}" --timeout=300s

echo "== probing ${UP} for ${DIM}d vectors"
# NodePort is firewalled from most hosts; probe from node1 itself.
MODEL="$([[ "${TARGET}" == "base" ]] && echo synanton-bge-base-embedding || echo synanton-bge-small-embedding)"
DIM_GOT="$(ssh -o ConnectTimeout=10 node1 "curl -s -m 30 -X POST http://localhost:${PORT}/v1/embeddings -H 'Content-Type: application/json' -d '{\"input\": \"swap check\", \"model\": \"${MODEL}\"}' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[\"data\"][0][\"embedding\"]))'")"
if [[ "${DIM_GOT}" != "${DIM}" ]]; then
  echo "ABORT: expected ${DIM}d, serving ${DIM_GOT:-unreachable}" >&2
  exit 1
fi
echo "OK: ${UP} serving ${DIM}d on node1:${PORT}"
