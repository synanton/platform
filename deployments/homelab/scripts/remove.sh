#!/usr/bin/env bash
# remove.sh — delete the namespace and the platform's static PVs.
# Prompts unless CONFIRM=yes. PVs are Retain: deleting the namespace leaves
# them Bound; this script deletes them explicitly (their data on the nodes
# stays — remove it manually if intended).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"

if [ "${CONFIRM:-}" != "yes" ]; then
    echo "This deletes namespace '$NAMESPACE' and the platform PVs (local-ssd + fixtures)."
    read -rp "Type 'delete' to confirm: " ans
    [ "$ans" = "delete" ] || { echo "aborted"; exit 1; }
fi

kubectl delete namespace "$NAMESPACE" --ignore-not-found --wait=true
kubectl delete pv -l app.kubernetes.io/part-of=synanton --ignore-not-found
kubectl delete pv slice-fixtures --ignore-not-found
echo "removed (node-local data under /mnt/local-fast is retained by policy)"
