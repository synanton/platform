#!/usr/bin/env bash
set -euo pipefail

# Reproduce the P0 compiler output from a clean checkout.
# Required: SYNANTON_TOKEN, RELEASE_ID, RELEASE_DIGEST.

PLATFORM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${SYNTOLOGY_URL:-http://localhost:8080}"
TENANT="${TENANT:-demo}"
ONTOLOGY="${ONTOLOGY:-it-books}"
RELEASE_ID="${RELEASE_ID:-}"
RELEASE_DIGEST="${RELEASE_DIGEST:-}"
OUTPUT="${OUTPUT:-$PLATFORM_ROOT/build/p0/it-books-v1}"
DEPLOYED_IMAGE="${DEPLOYED_IMAGE:-}"

if [[ -z "${SYNANTON_TOKEN:-}" || -z "$RELEASE_ID" || -z "$RELEASE_DIGEST" ]]; then
  echo "SYNANTON_TOKEN, RELEASE_ID and RELEASE_DIGEST are required" >&2
  exit 1
fi

args=(
  --base-url "$BASE_URL"
  --tenant "$TENANT"
  --ontology "$ONTOLOGY"
  --release-id "$RELEASE_ID"
  --release-digest "$RELEASE_DIGEST"
  --output "$OUTPUT"
)
if [[ -n "$DEPLOYED_IMAGE" ]]; then
  args+=(--deployed-image "$DEPLOYED_IMAGE")
fi

cd "$PLATFORM_ROOT"
./gradlew :java:extraction-compiler:run --args="${args[*]}"
