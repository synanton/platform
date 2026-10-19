#!/usr/bin/env bash
# verify-cluster.sh — the five pre-flight checks before deploy.sh.
#
# Read-only. Run after the cluster is up and BEFORE deploying: three of these
# classes have silently broken first runs before (cordoned workers, missing
# PVs, registry auth).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REGISTRY="${REGISTRY:-local-registry:5000}"
PROBE_IMAGE="${PROBE_IMAGE:-$REGISTRY/synanton/minio-mc:latest}"

FAIL=0
check() { printf '\n=== %s ===\n' "$*"; }
ok()    { printf '  ok    %s\n' "$*"; }
bad()   { printf '  FAIL  %s\n' "$*"; FAIL=1; }

check "1. registry pull from a worker (mirror-first: the image must exist locally first)"
# The probe runs on node2 (a compute worker); it uses an image already in the
# registry. If it fails, the two likely causes are covered by the messages.
if kubectl -n default run registry-probe --rm -i --restart=Never \
        --overrides='{"spec":{"nodeName":"node2"}}' \
        --image="$PROBE_IMAGE" --command -- echo ok >/dev/null 2>&1; then
    ok "pulled $PROBE_IMAGE on node2"
else
    bad "could not pull $PROBE_IMAGE on node2 — check the pull credentials and that the image is mirrored"
fi

check "2. local-ssd StorageClass exists (shared with gpu-runtime — do not modify)"
kubectl get sc local-ssd >/dev/null 2>&1 && ok "local-ssd present" || bad "local-ssd missing"

check "3. PVs available for the platform"
kubectl get pv -o custom-columns=NAME:.metadata.name,SC:.spec.storageClassName,CLAIM:.spec.claimRef.name \
    | grep -E 'local-ssd|slice-fixtures|longhorn' || true

check "4. node labels"
kubectl get nodes -L synanton.io/role --no-headers | awk '{printf "  %s role=%s\n", $1, $NF}'

check "5. workers schedulable (gpu-runtime's cluster-stop.sh parks them)"
cordoned=$(kubectl get nodes --no-headers | awk '$2 ~ /SchedulingDisabled/ {print $1}')
if [ -n "$cordoned" ]; then
    bad "cordoned: $cordoned — run scripts/start.sh (uncordons) or kubectl uncordon"
else
    ok "no cordoned workers"
fi

printf '\n'
[ "$FAIL" = "0" ] && echo "pre-flight: all checks passed" || { echo "pre-flight: FAILURES above" >&2; exit 1; }
