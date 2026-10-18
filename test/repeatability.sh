#!/usr/bin/env bash
# repeatability.sh — prove the acceptance run is hermetic and idempotent:
# two consecutive runs from a clean tree, with a residue check after each.
#
#   test/repeatability.sh                # two runs, shared stack (fast)
#   FULL_RESET=1 test/repeatability.sh   # teardown between runs (slow)
#
# The property under test is not "the criteria pass" — it is "the pass is
# reproducible and leaves nothing behind". A single clean run can be clean by
# accident; two runs prove run 1 left no residue and run 2 does not depend on
# it. The acceptance script's per-run fixture root + cleanup trap is what
# makes the fixture tree hermetic; this script is the guard that proves it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

COMPOSE_FILE="${COMPOSE_FILE:-deployments/docker/compose.yaml}"
FULL_RESET="${FULL_RESET:-0}"

step() { printf '\n=== %s ===\n' "$*"; }
die()  { printf 'FAIL %s\n' "$*" >&2; exit 1; }

if [ -n "$(git status --porcelain)" ]; then
    die "working tree is not clean before the repeatability check — commit or discard first:
$(git status --porcelain)"
fi

for i in 1 2; do
    step "Run ${i}/2"
    if [ "$FULL_RESET" = "1" ]; then
        # Wipes the compose project's volumes (content cache, kafka data) and
        # all containers — the strongest clean-state proof, at the cost of the
        # shared demo stack. Bind-mounted demo data (Cassandra) survives.
        docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true
    fi
    WORKER_PROFILE=compose tests/integration/vertical-slice.sh
    if [ -n "$(git status --porcelain)" ]; then
        die "run ${i} left residue in the working tree:
$(git status --porcelain)"
    fi
    printf 'run %d: criteria passed, tree clean\n' "$i"
done

step "Repeatability: two clean runs, no residue"
