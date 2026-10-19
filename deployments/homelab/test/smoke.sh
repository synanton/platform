#!/usr/bin/env bash
# smoke.sh — quick health check of the slice's pods.
set -euo pipefail
NAMESPACE="${NAMESPACE:-synanton}"
kubectl -n "$NAMESPACE" get pods -o wide
echo
kubectl -n "$NAMESPACE" get pods --no-headers | awk '
    $2 !~ /^([0-9]+)\/\1$/ { bad = 1; print "  not ready: " $1 " (" $2 ")" }
    END { exit bad }' \
    && echo "smoke: all pods ready" || { echo "smoke: pods not ready" >&2; exit 1; }
