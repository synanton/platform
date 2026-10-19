#!/usr/bin/env bash
# fault-inject.sh <point> — crash synflux at a named workflow point, then
# recover. Implements the scale-0 → clear → scale-up pattern: k8s would
# otherwise re-hit the fault on every restart (the env persists in the
# Deployment spec — the failure mode the #295 review named).
#
# The crash is detected by the terminated container's exit code 70 (durable
# across restarts; container logs are not).
#
# Usage: ./fault-inject.sh {after-discover|after-acquire|after-manifest}
set -euo pipefail

NAMESPACE="${NAMESPACE:-synanton}"
POINT="${1:?usage: fault-inject.sh {after-discover|after-acquire|after-manifest}}"
TIMEOUT="${TIMEOUT:-180}"
REPLICAS="${SYNFLUX_REPLICAS:-2}"

case "$POINT" in
    after-discover|after-acquire|after-manifest) ;;
    *) echo "unknown fault point: $POINT" >&2; exit 1 ;;
esac

echo "== enabling the fault ($POINT)"
kubectl -n "$NAMESPACE" set env deployment/synflux SYNANTON_FAULT_INJECT="$POINT"
kubectl -n "$NAMESPACE" rollout restart deployment/synflux

echo "== waiting for a worker to exit 70 (timeout ${TIMEOUT}s)"
deadline=$((SECONDS + TIMEOUT))
crashed=0
while [ $SECONDS -lt $deadline ]; do
    if kubectl -n "$NAMESPACE" get pods -l app=synflux -o json 2>/dev/null \
        | grep -q '"exitCode": 70'; then
        crashed=1
        break
    fi
    sleep 2
done
[ "$crashed" = "1" ] || { echo "timeout waiting for the injected crash" >&2; exit 1; }

# scale-0 FIRST (stop the restart), THEN clear the env, THEN scale up.
echo "== scale 0 → clear fault → scale ${REPLICAS}"
kubectl -n "$NAMESPACE" scale deployment/synflux --replicas=0
kubectl -n "$NAMESPACE" set env deployment/synflux SYNANTON_FAULT_INJECT-
kubectl -n "$NAMESPACE" scale deployment/synflux --replicas="$REPLICAS"
kubectl -n "$NAMESPACE" rollout status deployment/synflux --timeout=300s

echo "== recovered — check the operation status to confirm the workflow resumed"
