#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$REPO_ROOT/deployment/docker/compose.yaml"

PHASE="${1:-1}"
TENANT="${TENANT:-demo}"
INGEST_PATH="/demo-data/documents"

usage() {
  echo "Usage: $0 [--phase=1|2]"
  echo "  --phase=1  Run Phase 1 pipeline (filesystem → Cassandra + MinIO). Default."
  echo "  --phase=2  Run Phase 2 pipeline (adds LLM enrichment + embeddings; requires 2× 8 GB GPUs)."
  exit 1
}

for arg in "$@"; do
  case $arg in
    --phase=*) PHASE="${arg#*=}" ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $arg"; usage ;;
  esac
done

if [[ "$PHASE" != "1" && "$PHASE" != "2" ]]; then
  echo "Error: --phase must be 1 or 2"
  usage
fi

echo "=== Synanton Ingestion Demo (Phase $PHASE) ==="

# Build and start services
if [[ "$PHASE" == "2" ]]; then
  echo "[1/4] Starting Phase 2 stack (Cassandra + MinIO + vLLM + synvault + synflux)..."
  docker compose -f "$COMPOSE_FILE" --profile phase2 up -d --build
else
  echo "[1/4] Starting Phase 1 stack (Cassandra + MinIO + synvault + synflux)..."
  docker compose -f "$COMPOSE_FILE" up -d --build cassandra minio minio-init synvault synflux
fi

# Wait for synflux health
echo "[2/4] Waiting for synflux to be healthy..."
RETRIES=30
for i in $(seq 1 $RETRIES); do
  STATUS=$(docker compose -f "$COMPOSE_FILE" ps synflux --format json 2>/dev/null | \
    python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0].get('Health',''))" 2>/dev/null || echo "")
  if [[ "$STATUS" == "healthy" ]]; then
    echo "  synflux is healthy"
    break
  fi
  if [[ $i -eq $RETRIES ]]; then
    echo "Error: synflux did not become healthy in time"
    docker compose -f "$COMPOSE_FILE" logs synflux --tail=30
    exit 1
  fi
  echo "  waiting... ($i/$RETRIES)"
  sleep 5
done

if [[ "$PHASE" == "2" ]]; then
  echo "[2b/4] Waiting for vLLM services to be healthy (may take 3 minutes)..."
  for svc in vllm-llm vllm-embed; do
    for i in $(seq 1 36); do
      STATUS=$(docker compose -f "$COMPOSE_FILE" --profile phase2 ps "$svc" --format json 2>/dev/null | \
        python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0].get('Health',''))" 2>/dev/null || echo "")
      if [[ "$STATUS" == "healthy" ]]; then
        echo "  $svc is healthy"
        break
      fi
      if [[ $i -eq 36 ]]; then
        echo "Error: $svc did not become healthy within 3 minutes"
        exit 1
      fi
      echo "  waiting for $svc... ($i/36)"
      sleep 5
    done
  done
fi

# Trigger ingestion
SPRING_PROFILES=""
if [[ "$PHASE" == "2" ]]; then SPRING_PROFILES="phase2"; fi

echo "[3/4] Starting ingestion job (tenant=$TENANT, path=$INGEST_PATH)..."
SYNFLUX_PORT=$(docker compose -f "$COMPOSE_FILE" port synflux 8090 2>/dev/null | cut -d: -f2 || echo "8090")
RESPONSE=$(curl -sf "http://localhost:${SYNFLUX_PORT}/ingest/run" \
  -H "Content-Type: application/json" \
  -d "{\"tenant\":\"$TENANT\",\"source\":\"filesystem\",\"path\":\"$INGEST_PATH\"}")
JOB_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
echo "  Job started: $JOB_ID"

# Poll job status
echo "[4/4] Waiting for job to complete..."
for i in $(seq 1 120); do
  JOB_JSON=$(curl -sf "http://localhost:${SYNFLUX_PORT}/ingest/jobs/$JOB_ID?tenant=$TENANT")
  STATE=$(echo "$JOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['state'])")
  PROCESSED=$(echo "$JOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['processedCount'])")
  ERRORS=$(echo "$JOB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['errorCount'])")
  echo -ne "\r  state=$STATE processed=$PROCESSED errors=$ERRORS (${i}s)"

  if [[ "$STATE" == "SUCCEEDED" ]]; then
    echo ""
    echo ""
    echo "Ingested $PROCESSED documents from $INGEST_PATH (errors=$ERRORS)"
    echo ""
    echo "Verify:"
    SVPORT=$(docker compose -f "$COMPOSE_FILE" port synvault 8091 2>/dev/null | cut -d: -f2 || echo "8091")
    echo "  curl http://localhost:${SVPORT}/manifest/${TENANT}"
    exit 0
  elif [[ "$STATE" == "FAILED" ]]; then
    echo ""
    echo "Error: ingestion job FAILED (processed=$PROCESSED, errors=$ERRORS)"
    exit 1
  fi
  sleep 1
done

echo ""
echo "Error: timed out waiting for job to complete"
exit 1
