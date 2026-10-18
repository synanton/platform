#!/usr/bin/env bash
# Fix host ownership of bench bind-mount data dirs (needs sudo).
# Root cause pattern: docker/compose creates missing bind source dirs as
# root; services running as non-root then fail with AccessDenied at runtime.
# Verified 2026-10-11: postgres/minio/cassandra bench containers run as
# root (no fix needed); ONLY synquest runs non-root (synanton, uid 100:101
# per synquest.Dockerfile) and needs this after the data dir is (re)created.
#
# Usage: ./scripts/fix-bench-perms.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${ROOT_DIR}/deployments/docker/data/synquest"

if [[ ! -e "${TARGET}" ]]; then
  echo "absent (nothing to fix): ${TARGET}"
  exit 0
fi
echo "chown 100:101: ${TARGET} (synquest Lucene index)"
sudo chown -R 100:101 "${TARGET}"
echo "done. Restart synquest: docker compose -f deployments/docker/compose.yaml up -d synquest"
