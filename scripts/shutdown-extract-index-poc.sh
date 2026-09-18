#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$REPO_ROOT/deployment/docker/compose.yaml"

WIPE_VOLUMES=false
for arg in "$@"; do
  case "$arg" in
    --volumes|-v)
      WIPE_VOLUMES=true
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--volumes]" >&2
      exit 1
      ;;
  esac
done

echo "=== Shutting down Synanton extract + index PoC ==="

if docker ps -a --filter "name=^extraction-gateway$" --format '{{.Names}}' | grep -q extraction-gateway; then
  echo "[1/2] Stopping extraction-gateway (run separately from the content_extractor repo)..."
  docker rm -f extraction-gateway >/dev/null
  echo "  extraction-gateway removed"
else
  echo "[1/2] extraction-gateway is not running, skipping"
fi

echo "[2/2] Stopping platform stack (cassandra, minio, postgres, synvault, synflux, synquest)..."
if [[ "$WIPE_VOLUMES" == true ]]; then
  echo "  --volumes passed: also removing named volumes (cassandra/minio/postgres data)"
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans
else
  docker compose -f "$COMPOSE_FILE" down --remove-orphans
fi

echo "Done. Demo data volumes were left in place (pass --volumes to also wipe them)."
