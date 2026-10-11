#!/usr/bin/env bash
#
# Bench vector stacks for VEC-B5 compositions 2-5 (Milvus / Qdrant).
# Reproducible setup: edit variables, run top to bottom, verify with the
# HEALTH section at the end. Idempotent where cheap (existing names reused).
#
# Prerequisites: docker with bridge networking; images cached
# (milvusdb/milvus, qdrant/qdrant, quay.io/coreos/etcd, minio/minio).
# If pulls stall on docker.io, use the local registry mirror (see ops notes
# in the B5.2 thread) or: docker pull mirror.gcr.io/<image> + retag.
#
# Known host constraints (verified 2026-10-09, do not "fix" blindly):
# - Embedded Docker DNS is unreliable on user networks here: prefer explicit
#   container IPs or --add-host over bare names for cross-container traffic.
#   (Milvus ETCD_ENDPOINTS below uses the etcd container IP captured at start.)
# - Port 8091 is taken by gpu-7-external-gateway; synvault remaps to 18091
#   (deployment/docker/compose.yaml). Do not bind 8091 on this host.
# - Two compose files resolve to project name "docker" and recreate each
#   other's containers — start stacks explicitly (below), not via compose.
set -euo pipefail

# Data root for ALL bind mounts below (external volumes only — nothing may
# live on container overlayfs or docker-root; see External-storage rule).
# Export DATA_ROOT to relocate (local SSD/NVMe, never network filesystems).
DATA_ROOT="${DATA_ROOT:-./data}"
mkdir -p "${DATA_ROOT}"/{etcd,minio,milvus,qdrant}

# YDB client CA: repo-local gitignored copy (docker/certs/ydb/ca.pem) so legs
# survive /tmp wipes on reboot. Legs read YDB_CA_PATH first; refresh the copy
# whenever ydb-poc is recreated (certs regenerate with the container).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${REPO_ROOT}/docker/certs/ydb/ca.pem" ]]; then
  export YDB_CA_PATH="${REPO_ROOT}/docker/certs/ydb/ca.pem"
else
  echo "warning: ${REPO_ROOT}/docker/certs/ydb/ca.pem missing — copy it out with:" >&2
  echo "  docker cp ydb-poc:/ydb_certs/ca.pem ${REPO_ROOT}/docker/certs/ydb/ca.pem" >&2
fi

# ---------------------------------------------------------------- network ---
docker network create bench-vec 2>/dev/null || true

# -------------------------------------------------------------------- etcd ---
# Milvus dependency. Health: etcdctl endpoint health (see HEALTH section).
docker rm -f milvus-etcd 2>/dev/null || true
docker run -d --name milvus-etcd --network bench-vec \
  -v "${DATA_ROOT}/etcd:/etcd" \
  quay.io/coreos/etcd:v3.5.18 \
  etcd -advertise-client-urls=http://127.0.0.1:2379 \
       -listen-client-urls=http://0.0.0.0:2379 \
       --data-dir=/etcd

# ------------------------------------------------------------------- minio ---
# Milvus dependency (object storage). Credentials must match Milvus env below.
docker rm -f milvus-minio 2>/dev/null || true
docker run -d --name milvus-minio --network bench-vec \
  -v "${DATA_ROOT}/minio:/minio_data" \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio:latest server /minio_data

# ------------------------------------------------------------------ milvus ---
# Standalone. Needs etcd+minio healthy first (Milvus panics fast on etcd
# timeout instead of retrying — if it exits 134, check ETCD reachability,
# then `docker start milvus-standalone`).
ETCD_IP="$(docker inspect milvus-etcd --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
MINIO_IP="$(docker inspect milvus-minio --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
echo "etcd IP: ${ETCD_IP} / minio IP: ${MINIO_IP}"
docker rm -f milvus-standalone 2>/dev/null || true
docker run -d --name milvus-standalone --network bench-vec \
  -v "${DATA_ROOT}/milvus:/var/lib/milvus" \
  -e "ETCD_ENDPOINTS=${ETCD_IP}:2379" \
  -e "MINIO_ADDRESS=${MINIO_IP}:9000" \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest milvus run standalone
# NOTE: both endpoints by IP, not name — embedded DNS proved unreliable here
# (getent fails on fresh user networks; Milvus panics on etcd timeout instead
# of retrying, and dies ~60s later when MinIO is unreachable).

# ------------------------------------------------------------------ qdrant ---
# Single container. REST :6333, gRPC :6334 (plaintext for tests; TLS is the
# caller's concern — see QdrantVectorRetriever javadoc).
docker rm -f qdrant-bench 2>/dev/null || true
docker run -d --name qdrant-bench \
  -v "${DATA_ROOT}/qdrant:/qdrant/storage" \
  -p 6333:6333 -p 6334:6334 \
  qdrant/qdrant:latest

# ------------------------------------------------------------------ bench PG --
# PG+pgvector quest DB on :5433 (bench/bench). Data persists in
# docker/data/postgres (bind mount from docker-compose.bench.yml).
docker compose -f docker/docker-compose.bench.yml up -d postgres

# ------------------------------------------------------------------ ydb-poc ---
# Composition 6 backend. Restart the existing container (preserves data);
# re-copy the CA (location from dev-guide-tests.md lookup chain).
#   docker start ydb-poc
#   docker cp ydb-poc:/ydb_certs/ca.pem /tmp/ydb-ca.pem
# Cluster flags (hybrid search etc.) persist in the container; re-apply only
# on a fresh container (see dev-guide-tests.md).

# ------------------------------------------------------------------ HEALTH ---
# etcd:    docker exec milvus-etcd etcdctl endpoint health
# minio:   docker logs milvus-minio 2>&1 | tail -2
# milvus:  curl -s http://localhost:9091/healthz   (empty body + exit 0 is UP;
#          the Go routine dump in logs at startup is normal server operation)
# qdrant:  curl -s http://localhost:6333/          (JSON with version)
# pg:      docker exec bench-postgres-1 psql -U bench -d bench -c "SELECT 1"
# ydb:     test suite YdbSearchTestBase connects grpcs://localhost:2135/local
#
# ------------------------------------------------------------------ TEI tunnel
# Dense legs embed via node1 TEI (bge-base, 768d). This host's firewall drops
# NodePort traffic, so bench reaches TEI through SSH tunnels bound on all
# host interfaces (compose points synquest/synflux at host-gateway:30800).
# Idempotent: re-run anytime; live tunnels are left alone. Without them, dense
# runs fail closed (503) by design — never silently lexical.
#   NodePort object (bench-owned, gpu-runtime blueprint stays ClusterIP-only):
#   kubectl apply -f deployment/bench/tei-embedding-bench-svc.yaml  (from node0)
#
# TEI endpoint resolution (2026-10-11): K8s GPU injection on node1 is faulty
# (operator track), so TEI currently serves from manual docker on node1
# (tei-base-bench :8002, tei-small-bench :8001) instead of the NodePort.
# tei_endpoint() prefers the K8s path and falls back to manual docker.
tei_endpoint() { # $1 = model, $2 = k8s-nodeport, $3 = docker-port; echoes working node1 port or nothing
  local model="$1" k8s_port="$2" docker_port="$3" port
  for port in "${k8s_port}" "${docker_port}"; do
    if ssh -o ConnectTimeout=8 -o BatchMode=yes node1 \
      "curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST http://localhost:${port}/v1/embeddings -H 'Content-Type: application/json' -d '{\"input\": \"t\", \"model\": \"${model}\"}'" \
      2>/dev/null | grep -q 200; then
      echo "${port}"
      return 0
    fi
  done
  return 1
}
ensure_tunnel() { # $1 = host port, $2 = node1 port, $3 = label
  if (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -q ":$1 "; then
    echo "tei tunnel: already listening on :$1 ($3)"
  else
    ssh -o ConnectTimeout=10 -o BatchMode=yes -fN -L "0.0.0.0:$1:localhost:$2" node1 \
      && echo "tei tunnel: established :$1 -> node1:$2 ($3)" \
      || echo "tei tunnel: FAILED ($3 dense legs will 503) - rerun when node1 is reachable"
  fi
}
if BASE_PORT="$(tei_endpoint synanton-bge-base-embedding 30800 8002)"; then
  ensure_tunnel 30800 "${BASE_PORT}" "base-768d"
else
  echo "tei tunnel: FAILED (no live base endpoint on node1:30800 or :8002)"
fi
curl -s -m 15 -o /dev/null -w "tei /v1/embeddings via tunnel: %{http_code}\n" \
  -X POST http://localhost:30800/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input": "tunnel check", "model": "synanton-bge-base-embedding"}' || true
# 384d leg (F-9): bge-small TEI on node1 (manual docker :8001 while K8s GPU
# injection is faulty; K8s NodePort :30801 once the small workload schedules).
if SMALL_PORT="$(tei_endpoint synanton-bge-small-embedding 30801 8001)"; then
  ensure_tunnel 30801 "${SMALL_PORT}" "small-384d"
else
  echo "tei-small tunnel: FAILED (no live small endpoint on node1:30801 or :8001)"
fi
curl -s -m 15 -o /dev/null -w "tei-small /v1/embeddings via tunnel: %{http_code}\n" \
  -X POST http://localhost:30801/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input": "tunnel check", "model": "synanton-bge-small-embedding"}' || true
#
# Teardown (data in bind mounts / named containers survives unless removed):
#   docker stop milvus-standalone milvus-minio milvus-etcd qdrant-bench
