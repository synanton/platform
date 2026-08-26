#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$REPO_ROOT/deployment/docker/compose.yaml"
TENANT="${TENANT:-demo}"

echo "=== Synanton extract + index PoC ==="

echo "[1/5] Starting Cassandra, MinIO, extraction-gateway, synvault, synflux, synquest..."
docker compose -f "$COMPOSE_FILE" up -d --build \
  cassandra minio minio-init extraction-gateway synvault synflux synquest

wait_healthy() {
  local svc="$1"
  local retries="${2:-40}"
  for i in $(seq 1 "$retries"); do
    STATUS=$(docker compose -f "$COMPOSE_FILE" ps "$svc" --format json 2>/dev/null | \
      python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0].get('Health','') if isinstance(data,list) else data.get('Health',''))" 2>/dev/null || echo "")
    if [[ "$STATUS" == "healthy" ]]; then
      echo "  $svc is healthy"
      return 0
    fi
    echo "  waiting for $svc... ($i/$retries)"
    sleep 5
  done
  echo "Error: $svc did not become healthy"
  docker compose -f "$COMPOSE_FILE" logs "$svc" --tail=40
  exit 1
}

wait_healthy extraction-gateway 36
wait_healthy synflux 36
wait_healthy synquest 36

SYNFLUX_PORT=$(docker compose -f "$COMPOSE_FILE" port synflux 8090 | cut -d: -f2)
SYNQUEST_PORT=$(docker compose -f "$COMPOSE_FILE" port synquest 8083 | cut -d: -f2)

echo "[2/5] Starting ingestion..."
RESPONSE=$(curl -sf "http://localhost:${SYNFLUX_PORT}/ingest/run" \
  -H "Content-Type: application/json" \
  -d "{\"tenant\":\"$TENANT\",\"source\":\"filesystem\",\"path\":\"/demo-data/documents\"}")
JOB_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
echo "  job $JOB_ID"

for i in $(seq 1 180); do
  JOB_JSON=$(curl -sf "http://localhost:${SYNFLUX_PORT}/ingest/jobs/$JOB_ID?tenant=$TENANT")
  STATE=$(echo "$JOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['state'])")
  PROCESSED=$(echo "$JOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['processedCount'])")
  echo -ne "\r  state=$STATE processed=$PROCESSED (${i}s)"
  if [[ "$STATE" == "SUCCEEDED" ]]; then
    echo ""
    break
  elif [[ "$STATE" == "FAILED" ]]; then
    echo ""
    echo "Ingestion failed: $JOB_JSON"
    exit 1
  fi
  if [[ "$i" -eq 180 ]]; then
    echo ""
    echo "Timed out waiting for ingestion"
    exit 1
  fi
  sleep 1
done

echo "[3/5] Reindexing synquest..."
curl -sf -X POST "http://localhost:${SYNQUEST_PORT}/reindex?tenant=${TENANT}" >/dev/null
echo "  reindex done"

echo "[4/5] Searching..."
QUERY="${1:-Acme supplies}"
SEARCH=$(curl -sf "http://localhost:${SYNQUEST_PORT}/search" \
  -H "Content-Type: application/json" \
  -d "{\"tenant\":\"$TENANT\",\"query\":\"$QUERY\",\"top_k\":5}")
echo "$SEARCH" | python3 -m json.tool

echo "[5/5] Hits should include source_uri, page_start, section_path when structured extraction succeeded."
