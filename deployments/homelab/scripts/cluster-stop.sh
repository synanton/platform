#!/usr/bin/env bash
# cluster-stop.sh — full-cluster shutdown entry point.
#
# Normal platform stop is local and does not depend on a sibling checkout.
# Full-cluster cordon/drain/poweroff is explicit via --full or --poweroff.
# Without those flags this script only stops the Synanton namespace.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
GPU_RUNTIME_ROOT="${GPU_RUNTIME_ROOT:-$PLATFORM_ROOT/../gpu-runtime}"
TARGET="$GPU_RUNTIME_ROOT/deployments/homelab/scripts/cluster-stop.sh"

full=0
for arg in "$@"; do
    case "$arg" in
        --full|--poweroff) full=1 ;;
    esac
done

if [ "$full" = "1" ]; then
    if [[ ! -x "$TARGET" ]]; then
        echo "error: --full/--poweroff requires gpu-runtime cluster-stop.sh at $TARGET" >&2
        echo "set GPU_RUNTIME_ROOT or run scripts/stop.sh for a platform-only stop" >&2
        exit 1
    fi
    exec "$TARGET" "$@"
fi

exec "$SCRIPT_DIR/stop.sh"
