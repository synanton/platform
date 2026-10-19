#!/usr/bin/env bash
# verify-cluster.sh — the five pre-flight checks before deploy.sh.
#
# Read-only except for the bootstrap it needs to make check 1 meaningful:
# the namespace and its registry pull secret (a probe without credentials
# would fail ImagePullBackOff for the wrong reason).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
REGISTRY="${REGISTRY:-local-registry:5000}"
# The probe uses an image that is already mirrored (minio-mc ships with the
# slice). Override if your mirror uses another tag.
PROBE_IMAGE="${PROBE_IMAGE:-$REGISTRY/synanton/minio-mc:latest}"

FAIL=0
check() { printf '\n=== %s ===\n' "$*"; }
ok()    { printf '  ok    %s\n' "$*"; }
bad()   { printf '  FAIL  %s\n' "$*"; FAIL=1; }

check "1. registry pull from a worker"
# The probe pod lives in the slice namespace WITH the pull secret; the
# registry requires basic auth and worker nodes have no assumed egress.
kubectl apply -f "$ROOT/k8s/namespace.yaml" >/dev/null
"$SCRIPT_DIR/create-registry-secret.sh" >/dev/null
# kubectl's attach fallback prints the container output on stderr — capture
# both streams, and match the exact line (noise contains the image name).
probe_out=$(kubectl -n "$NAMESPACE" run registry-probe --rm -i --restart=Never \
    --overrides="{\"spec\":{\"nodeName\":\"node2\",\"imagePullSecrets\":[{\"name\":\"local-registry-cred\"}]}}" \
    --image="$PROBE_IMAGE" --command -- echo registry-pull-ok 2>&1 || true)
if grep -q 'registry-pull-ok' <<<"$probe_out"; then
    ok "pulled $PROBE_IMAGE on node2"
else
    bad "could not pull $PROBE_IMAGE on node2 — check the mirror tag and the pull secret"
    printf '      %s\n' "$(echo "$probe_out" | tail -2)"
fi

check "2. local-ssd StorageClass exists (shared with gpu-runtime — do not modify)"
kubectl get sc local-ssd >/dev/null 2>&1 && ok "local-ssd present" || bad "local-ssd missing"

check "3. platform PVs known to the cluster"
kubectl get pv -o custom-columns=NAME:.metadata.name,SC:.spec.storageClassName,CLAIM:.spec.claimRef.name \
    | grep -E 'local-ssd|slice-fixtures|longhorn' || ok "none yet (deploy.sh applies them)"

check "4. node labels"
missing=""
for n in node1 node2 node3; do
    role=$(kubectl get node "$n" -o jsonpath='{.metadata.labels.synanton\.io/role}' 2>/dev/null || true)
    printf '  %s role=%s\n' "$n" "${role:-<none>}"
    case "$n:$role" in
        node1:cassandra-seed|node2:compute|node3:compute) ;;
        *) missing="$missing $n" ;;
    esac
done
[ -z "$missing" ] && ok "labels match the placement plan" \
    || bad "missing/wrong labels on:$missing — apply the labels in cluster-as-built.md"

check "5. workers schedulable (gpu-runtime's cluster-stop.sh parks them)"
cordoned=$(kubectl get nodes --no-headers | awk '$2 ~ /SchedulingDisabled/ {print $1}')
if [ -n "$cordoned" ]; then
    bad "cordoned: $cordoned — run 'kubectl uncordon <node>' (scripts/start.sh also uncordons, but it expects the slice to be deployed)"
else
    ok "no cordoned workers"
fi

printf '\n'
if [ "$FAIL" = "0" ]; then
    echo "pre-flight: all checks passed"
else
    echo "pre-flight: FAILURES above" >&2
    exit 1
fi
