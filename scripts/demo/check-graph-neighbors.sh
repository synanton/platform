#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

rb010=$(./scripts/demo/run-query.sh --neighbors rb010)
grep -q 'neighbors=12 ' <<<"$rb010"
grep -q 'neighbors=14 ' <<<"$rb010"
grep -q 'neighbors=16 ' <<<"$rb010"

empty=$(./scripts/demo/run-query.sh --neighbors '4e54d1ae-7e10-528c-9bcb-e49160c1145d#26')
grep -q 'neighbors=0 by_kind={}' <<<"$empty"

if ./scripts/demo/run-query.sh --neighbors 'does-not-exist' >/tmp/graph-unknown.out 2>&1; then
  echo "error: unknown chunk did not return 404" >&2
  exit 1
fi
grep -q 'HTTP 404' /tmp/graph-unknown.out

echo "graph neighbors OK: rb010 12/14/16, empty 0, unknown 404"
