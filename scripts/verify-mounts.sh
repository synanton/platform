#!/bin/bash
# Fail-fast mount check for benchmark pre-run: every listed directory must be
# bind- or named-volume mounted (not overlayfs). Usage:
#   ./scripts/verify-mounts.sh <container> <dir> [<dir>...]
# Config sets intent; this check enforces observation.
set -u
container="$1"; shift
fail=0
for dir in "$@"; do
  if docker inspect "$container" --format '{{range .Mounts}}{{.Destination}}{{"\n"}}{{end}}' \
      2>/dev/null | grep -qx "$dir"; then
    echo "OK: $dir mounted in $container"
  else
    echo "FAIL: $dir NOT mounted in $container (overlayfs — see External-storage rule)" >&2
    fail=1
  fi
done
exit $fail
