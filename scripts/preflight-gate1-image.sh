#!/usr/bin/env bash
# Read-only Gate 1 image-delivery preflight.
# The approved image must already be the running production vLLM image on node3.
# No namespace pull secret, registry request, GPU allocation, or pod creation.
set -euo pipefail

NAMESPACE="${NAMESPACE:-gpu-plane}"
EXPECTED_NODE="${EXPECTED_NODE:-node3}"
EXPECTED_DIGEST="${EXPECTED_DIGEST:-sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b}"

fail() { printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }

pod_json="$(kubectl -n "$NAMESPACE" get pods -l app=vllm-extraction \
  --field-selector=status.phase=Running -o json 2>/dev/null)" || fail "cannot read production vLLM pod"
count="$(printf '%s' "$pod_json" | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items",[])))')"
[ "$count" -ge 1 ] || fail "no running production vLLM pod to establish image provenance"

read -r node image_id image_ref <<<"$(printf '%s' "$pod_json" | python3 -c '
import json,sys
items=json.load(sys.stdin)["items"]
p=items[0]
c=p["status"]["containerStatuses"][0]
print(p["spec"].get("nodeName",""), c.get("imageID",""), p["spec"]["containers"][0].get("image",""))
')"

[ "$node" = "$EXPECTED_NODE" ] || fail "production image provenance is on '$node', expected '$EXPECTED_NODE'"
case "$image_id" in
  *"$EXPECTED_DIGEST") ;;
  *) fail "production image digest '$image_id' does not match approved '$EXPECTED_DIGEST'" ;;
esac
if ! printf '%s' "$image_ref" | grep -q 'local-registry:5000/vllm-openai'; then
  fail "unexpected image repository: $image_ref"
fi

APPROVED_IMAGE="local-registry:5000/vllm-openai@${EXPECTED_DIGEST}"
printf 'PROVENANCE: approved digest matches the running node3 container\n'
printf 'image=%s\nnode=%s\ndigest=%s\n' "$APPROVED_IMAGE" "$node" "$EXPECTED_DIGEST"
if [ -n "${POD_MANIFEST:-}" ]; then
  [ -f "$POD_MANIFEST" ] || fail "POD_MANIFEST not found: $POD_MANIFEST"
  grep -q "nodeName: $EXPECTED_NODE" "$POD_MANIFEST" || fail "manifest must pin nodeName: $EXPECTED_NODE"
  grep -q "imagePullPolicy: IfNotPresent" "$POD_MANIFEST" || fail "manifest must use imagePullPolicy: IfNotPresent"
  grep -q "$EXPECTED_DIGEST" "$POD_MANIFEST" || fail "manifest is missing approved image digest"
  if grep -Eq '^[[:space:]]*imagePullSecrets:' "$POD_MANIFEST"; then
    fail "manifest must not contain imagePullSecrets"
  fi
  printf 'PASS: manifest pins node3, approved digest, IfNotPresent, and no pull secret\n'
fi

if [ "${CACHE_PROBE_VERIFIED:-0}" != "1" ]; then
  fail "digest provenance is confirmed, but cache/no-secret startup is unverified; Gate 1 remains blocked (see docs/acceptance/artifacts/fp8-gate1/image-cache-probe-2026-11-18.txt)"
fi
[ -n "${CACHE_PROBE_EVIDENCE:-}" ] || fail "CACHE_PROBE_EVIDENCE must point to a recorded no-GPU probe result"
[ -f "$CACHE_PROBE_EVIDENCE" ] || fail "CACHE_PROBE_EVIDENCE not found: $CACHE_PROBE_EVIDENCE"
grep -Eq '^Result:[[:space:]]*PASS' "$CACHE_PROBE_EVIDENCE" || fail "cache probe evidence does not record PASS"

printf 'PASS: cache probe evidence accepted; use this digest with nodeName: node3 and imagePullPolicy: IfNotPresent; do not create a namespace pull secret.\n'
