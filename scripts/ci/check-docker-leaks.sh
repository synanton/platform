#!/usr/bin/env bash
# Report Docker resources owned by one test/dev run. Read-only: never cleans up.
set -euo pipefail

RUN=""
PROJECT=""
TYPES="containers,networks,volumes"

usage() {
  echo "usage: $0 --run <id> [--compose-project <name>] [--types containers,networks,volumes]" >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --run) RUN="${2:-}"; shift 2 ;;
    --compose-project) PROJECT="${2:-}"; shift 2 ;;
    --types) TYPES="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) usage ;;
  esac
done

if [ -z "$RUN" ] && [ -z "$PROJECT" ]; then
  usage
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "FAIL: docker is not available; cannot verify leaks" >&2
  exit 2
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
FOUND=0

collect() {
  local kind="$1"
  : > "$TMP"
  if [ -n "$RUN" ]; then
    case "$kind" in
      containers) docker ps -a --filter "label=ogce.run=$RUN" --format '{{.ID}}\t{{.Names}}\t{{.Label "ogce.run"}}' >> "$TMP" ;;
      networks) docker network ls --filter "label=ogce.run=$RUN" --format '{{.ID}}\t{{.Name}}\t{{.Label "ogce.run"}}' >> "$TMP" ;;
      volumes) docker volume ls --filter "label=ogce.run=$RUN" --format '{{.Name}}\t{{.Label "ogce.run"}}' >> "$TMP" ;;
    esac
  fi
  if [ -n "$PROJECT" ]; then
    case "$kind" in
      containers) docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.ID}}\t{{.Names}}\t{{.Label "com.docker.compose.project"}}' >> "$TMP" ;;
      networks) docker network ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.ID}}\t{{.Name}}\t{{.Label "com.docker.compose.project"}}' >> "$TMP" ;;
      volumes) docker volume ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}\t{{.Label "com.docker.compose.project"}}' >> "$TMP" ;;
    esac
  fi
  sort -u -o "$TMP" "$TMP"
  if [ -s "$TMP" ]; then
    echo "LEAK $kind (run=${RUN:-none} project=${PROJECT:-none}):"
    cat "$TMP"
    FOUND=1
  fi
}

IFS=',' read -r -a TYPE_LIST <<< "$TYPES"
for kind in "${TYPE_LIST[@]}"; do
  case "$kind" in
    containers|networks|volumes) collect "$kind" ;;
    *) echo "FAIL: unsupported resource type '$kind'" >&2; exit 2 ;;
  esac
done

if [ "$FOUND" -ne 0 ]; then
  echo "FAIL: docker leak check found resources owned by this run; evidence preserved" >&2
  exit 1
fi
echo "PASS: no owned docker resources remain (run=${RUN:-none} project=${PROJECT:-none} types=$TYPES)"
