#!/usr/bin/env bash
# Enable the checked-in git hooks for this clone (runtime-data guard).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
echo "core.hooksPath -> .githooks (pre-commit runtime-data guard active)"
