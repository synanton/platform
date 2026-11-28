#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PROJECT_NAME="${PROJECT_NAME:-synanton-local-real-demo}"
COMPOSE=(docker compose
  --project-name "$PROJECT_NAME"
  -f deployments/docker/compose.yaml
  -f deployments/docker/compose.local-real-demo.yaml
  --profile local-real-demo)
REQUIRED_SOURCE=java/relix/src/main/java/org/synanton/relix/adapter/out/graph/neo4j/Neo4jSchemaVerifier.java

mapfile -t required < <(
  sed -n '/REQUIRED_CONSTRAINTS = Set.of(/,/);/p' "$REQUIRED_SOURCE" |
    grep -Eo '"[a-z0-9_]+_unique"' |
    tr -d '"' |
    sort
)

mapfile -t actual < <(
  "${COMPOSE[@]}" exec -T neo4j sh -c \
    'cypher-shell -a bolt://localhost:7687 -u neo4j -p "${NEO4J_AUTH#*/}" "SHOW CONSTRAINTS YIELD name RETURN name"' |
    sed -n 's/^"\(.*\)"$/\1/p' |
    sort
)

missing=()
for name in "${required[@]}"; do
  found=0
  for present in "${actual[@]}"; do
    [ "$name" = "$present" ] && { found=1; break; }
  done
  [ "$found" = 1 ] || missing+=("$name")
done

printf 'Required constraints (%d):\n' "${#required[@]}"
printf '  %s\n' "${required[@]}"
printf 'Observed constraints (%d):\n' "${#actual[@]}"
printf '  %s\n' "${actual[@]}"

if [ "${#missing[@]}" -gt 0 ]; then
  printf 'MISSING: %s\n' "${missing[*]}" >&2
  exit 1
fi

printf 'PASS: local-real-demo Neo4j has every required constraint\n'
