#!/usr/bin/env bash
# Orphaned-GPU-container watchdog (runs anywhere with ssh to node1 + kubectl
# to the cluster; suggested: cron on node0 every 5 min).
# Failure mode of 2026-10-10: a containerd container holding VRAM with no live
# owning pod is invisible to kubectl (capacity=1, allocatable=0, nothing
# Running) and self-sustains admission lockout.
#
# Rule: VRAM held while ZERO pods run in gpu-plane == definite orphan -> alert.
# VRAM held with a Running pod present == healthy (no output, no noise).
# Install on node0:  */5 * * * * /home/aminin/workspace/synanton/platform/scripts/bench-tei-orphan-watch.sh
set -euo pipefail

HOLDERS="$(ssh -o ConnectTimeout=10 node1 \
  "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null" | grep -vc '^$' || true)"
RUNNING="$(kubectl -n gpu-plane get pods --no-headers 2>/dev/null | awk '$3=="Running"' | grep -c . || true)"

if [[ "${HOLDERS}" != "0" && "${RUNNING}" == "0" ]]; then
  echo "GPU-ORPHAN-ALERT at $(date -u +%FT%TZ): ${HOLDERS} VRAM holder(s) on node1 with no Running pod in gpu-plane."
  echo "Diagnose: ssh node1 \"ps -o pid,ppid,cmd -p \$(nvidia-smi --query-compute-apps=pid --format=csv,noheader | tr '\n' ',')\""
  echo "Fix (node1 sudo): ctr -n k8s.io tasks kill -s SIGKILL <cid>; ctr -n k8s.io containers rm <cid>"
  echo "See gpu-runtime troubleshooting.md, orphaned-GPU-container entry."
fi
