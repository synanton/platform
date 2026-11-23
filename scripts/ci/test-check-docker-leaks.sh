#!/usr/bin/env bash
# Synthetic leak detection test. Creates only labeled/unlabeled resources it owns,
# verifies the checker, and removes them on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT/scripts/ci/check-docker-leaks.sh"
RUN="ogce-test-$(date +%s)-$$"
UNLABELED="ogce-unlabeled-$RUN"
CONTAINER="ogce-labeled-$RUN"
VOLUME="ogce-labeled-vol-$RUN"
NETWORK="ogce-labeled-net-$RUN"

cleanup() {
  docker rm -f "$CONTAINER" "$UNLABELED" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "1) unlabeled resource is outside the run label scope"
docker run -d --name "$UNLABELED" alpine:3.20 sleep 60 >/dev/null
set +e
OUTPUT="$("$CHECK" --run "$RUN" --types containers,networks,volumes 2>&1)"
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: unlabeled resource should not be selected" >&2
  echo "$OUTPUT" >&2
  exit 1
fi
echo "PASS: unlabeled container was not selected"

echo "2) labeled resources fail verification with names/IDs"
docker volume create --label ogce.dev=true --label "ogce.run=$RUN" "$VOLUME" >/dev/null
docker network create --label ogce.dev=true --label "ogce.run=$RUN" "$NETWORK" >/dev/null
docker run -d --name "$CONTAINER" --label ogce.dev=true --label "ogce.run=$RUN" \
  alpine:3.20 sleep 60 >/dev/null
set +e
OUTPUT="$("$CHECK" --run "$RUN" --types containers,networks,volumes 2>&1)"
RC=$?
set -e
if [ "$RC" -ne 1 ]; then
  echo "FAIL: labeled leak should fail the checker (rc=$RC)" >&2
  echo "$OUTPUT" >&2
  exit 1
fi
for name in "$CONTAINER" "$VOLUME" "$NETWORK"; do
  if ! printf '%s\n' "$OUTPUT" | grep -q "$name"; then
    echo "FAIL: leak output missing $name" >&2
    echo "$OUTPUT" >&2
    exit 1
  fi
done
echo "PASS: labeled leak detected with resource identities"

echo "3) cleanup is out of scope for the checker"
if ! printf '%s\n' "$OUTPUT" | grep -q "evidence preserved"; then
  echo "FAIL: checker did not preserve evidence" >&2
  exit 1
fi
echo "PASS: checker preserved evidence and did not clean up"
