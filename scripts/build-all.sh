#!/usr/bin/env bash
# Builds every active Java module. UI build will be added in Step 5.

set -euo pipefail

cd "$(dirname "$0")/.."
exec ./gradlew buildAll "$@"
