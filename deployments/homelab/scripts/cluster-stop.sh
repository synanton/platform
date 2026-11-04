#!/usr/bin/env bash
# cluster-stop.sh — platform entry point for the full-cluster shutdown.
#
# Delegates to gpu-runtime's cluster-stop.sh, which quiesces gpu-plane and
# synanton, cordons/drains workers, and optionally powers the nodes off.
# Examples:
#   ./scripts/cluster-stop.sh                # quiesce, do not power off
#   ./scripts/cluster-stop.sh --poweroff     # quiesce + power off all nodes
#   ./scripts/cluster-stop.sh --poweroff --yes --skip-snapshot
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
GPU_RUNTIME_ROOT="${GPU_RUNTIME_ROOT:-$PLATFORM_ROOT/../gpu-runtime}"
TARGET="$GPU_RUNTIME_ROOT/deployments/homelab/scripts/cluster-stop.sh"

if [[ ! -x "$TARGET" ]]; then
    echo "error: cluster stop script not found or not executable: $TARGET" >&2
    echo "set GPU_RUNTIME_ROOT to the gpu-runtime checkout" >&2
    exit 1
fi

exec "$TARGET" "$@"
