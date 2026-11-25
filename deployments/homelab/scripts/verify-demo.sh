#!/usr/bin/env bash
# verify-demo.sh — deployment health report for the user-facing demo.
# Read-only. Required failures set exit 1; deferred capabilities are warnings.
set -euo pipefail

NAMESPACE="${NAMESPACE:-synanton}"
FAIL=0
WARN=0
ok()   { printf '  ok      %s\n' "$*"; }
warn() { printf '  warning %s\n' "$*"; WARN=1; }
bad()  { printf '  FAIL    %s\n' "$*"; FAIL=1; }

command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || { echo "namespace $NAMESPACE missing" >&2; exit 1; }

echo "=== workload readiness ==="
for d in security ui synflux synquest extraction-gateway minio relix syntology; do
    if kubectl -n "$NAMESPACE" get deployment "$d" >/dev/null 2>&1; then
        ready=$(kubectl -n "$NAMESPACE" get deployment "$d" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
        desired=$(kubectl -n "$NAMESPACE" get deployment "$d" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
        if [ "${ready:-0}" = "${desired:-0}" ] && [ "${desired:-0}" != "0" ]; then
            ok "deployment/$d ready=$ready/$desired"
        else
            bad "deployment/$d ready=${ready:-0}/$desired"
        fi
    else
        bad "required deployment missing: $d"
    fi
done
for s in cassandra postgres neo4j; do
    if kubectl -n "$NAMESPACE" get statefulset "$s" >/dev/null 2>&1; then
        ready=$(kubectl -n "$NAMESPACE" get statefulset "$s" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
        desired=$(kubectl -n "$NAMESPACE" get statefulset "$s" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
        if [ "${ready:-0}" = "${desired:-0}" ] && [ "${desired:-0}" != "0" ]; then
            ok "statefulset/$s ready=$ready/$desired"
        else
            bad "statefulset/$s ready=${ready:-0}/$desired"
        fi
    else
        bad "required statefulset missing: $s"
    fi
done

echo "=== image provenance ==="
kubectl -n "$NAMESPACE" get pods -o custom-columns='POD:.metadata.name,IMAGE:.spec.containers[0].image,IMAGE_ID:.status.containerStatuses[0].imageID' --no-headers | sort

echo "=== UI ingress/workflow bundle ==="
if kubectl -n "$NAMESPACE" get deployment ui >/dev/null 2>&1; then
    nginx_conf=$(kubectl -n "$NAMESPACE" exec deploy/ui -- nginx -T 2>/dev/null || true)
    for route in '/synflux/' '/synquest/' 'api/v1/documents'; do
        grep -q "$route" <<<"$nginx_conf" && ok "UI route $route present" \
            || bad "UI route $route missing (stale UI image)"
    done
    if kubectl -n "$NAMESPACE" exec deploy/ui -- sh -c \
        'grep -Rqm1 "Document Workflow" /usr/share/nginx/html/assets 2>/dev/null'; then
        ok "Workflow bundle present"
    else
        bad "Workflow bundle missing (stale UI image)"
    fi
else
    bad "ui deployment missing"
fi

echo "=== synflux extraction API ==="
if kubectl -n "$NAMESPACE" get deployment synflux >/dev/null 2>&1; then
    probe=$(kubectl -n "$NAMESPACE" exec deploy/synflux -- sh -c \
        'wget -qO- -S "http://localhost:8090/v1/extraction/documents" 2>&1 || true' 2>/dev/null || true)
    grep -q '401' <<<"$probe" && ok "GET /v1/extraction/documents requires auth (route present)" \
        || bad "GET /v1/extraction/documents missing or unexpected"
else
    bad "synflux deployment missing"
fi

echo "=== topology / deferred capabilities ==="
if kubectl -n "$NAMESPACE" get deployment topology >/dev/null 2>&1; then
    ok "topology deployment present"
else
    warn "topology deployment absent — explicitly excluded from this demo"
fi
warn "MCP deferred (#887); Search/Chat deferred (#886) — not production-ready claims"

echo
if [ "$FAIL" = "0" ]; then
    if [ "$WARN" = "0" ]; then
        echo "demo verification: PASS"
    else
        echo "demo verification: PASS with deferred warnings"
    fi
    exit 0
else
    echo "demo verification: FAIL" >&2
    exit 1
fi
