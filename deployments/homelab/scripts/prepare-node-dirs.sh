#!/usr/bin/env bash
# prepare-node-dirs.sh — create the node-local directories the static
# local-ssd PVs point at (kubelet does not create `local.path` for you; a
# missing path leaves the pod unmountable). Idempotent; mode 0777 because the
# containers run as non-root users (dev cluster).
#
# Layout (see k8s/storage/local-ssd-pvs.yaml):
#   node1: /mnt/local-fast/{cassandra-0,postgres,minio,synquest}
#          /mnt/WD6000/nfs_share/synanton-slice  (fixtures export)
#   node2: /mnt/local-fast/cassandra-1
#   node3: /mnt/local-fast/cassandra-2
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${NAMESPACE:-synanton}"
IMAGE="${IMAGE:-local-registry:5000/synanton/alpine:3}"

prepare() {  # prepare <node> <dirs...>
    local node="$1"; shift
    local dirs="$*"
    local pod="prepare-${node}"
    kubectl -n "$NAMESPACE" delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n "$NAMESPACE" run "$pod" --restart=Never --image="$IMAGE" \
        --overrides="{\"spec\":{\"nodeName\":\"${node}\",\"imagePullSecrets\":[{\"name\":\"local-registry-cred\"}],\"containers\":[{\"name\":\"${pod}\",\"image\":\"${IMAGE}\",\"command\":[\"sh\",\"-c\",\"mkdir -p ${dirs} && chmod 0777 ${dirs} && echo prepared-${node}\"],\"volumeMounts\":[{\"name\":\"host\",\"mountPath\":\"/host\"}]}],\"volumes\":[{\"name\":\"host\",\"hostPath\":{\"path\":\"/mnt\"}}]}}" \
        >/dev/null
    kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$pod" --timeout=120s >/dev/null
    echo "prepared $node: $dirs"
    kubectl -n "$NAMESPACE" delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
}

prepare node1 "/host/local-fast/cassandra-0 /host/local-fast/postgres /host/local-fast/minio /host/local-fast/synquest /host/WD6000/nfs_share/synanton-slice"
prepare node2 "/host/local-fast/cassandra-1"
prepare node3 "/host/local-fast/cassandra-2"
echo "done"
