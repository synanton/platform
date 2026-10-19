#!/usr/bin/env bash
# run-security-provider.sh — start the security-provider from source for
# local development (the downstream trust predicate, ADR-019 / #252, needs it:
# synapt/relix validate every business request through
# POST /v1/security-context/validate).
#
# The provider's own compose stack lives in the security-provider repo; this
# script is the no-Docker dev path (Go must be installed).
#
# Prereqs:
#   - the security-provider repo checked out as a sibling (../security-provider)
#   - its config: see ../security-provider/configs/ (a signing key + issuer)
#
# Usage: ./scripts/run-security-provider.sh [port]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_ROOT="${SECURITY_PROVIDER_ROOT:-$SCRIPT_DIR/../../security-provider}"
PORT="${1:-8080}"

[ -d "$PROVIDER_ROOT" ] || { echo "security-provider not found at $PROVIDER_ROOT (set SECURITY_PROVIDER_ROOT)" >&2; exit 1; }
command -v go >/dev/null || { echo "go is required for the source dev path" >&2; exit 1; }

# configs/local.yaml is the no-TLS dev profile (stub identity, empty
# providers, local verification). The signing key comes from the environment;
# the provider never reads it from the committed config.
export SECURITY_PROVIDER_HTTP_ADDR=":$PORT"
export SECURITY_PROVIDER_SIGNING_KEY="${SECURITY_PROVIDER_SIGNING_KEY:-dev-signing-key-at-least-32-bytes!!}"
export SECURITY_PROVIDER_ADMIN_TOKEN="${SECURITY_PROVIDER_ADMIN_TOKEN:-dev-admin-token-at-least-32-bytes!!}"

echo "starting security-provider on :$PORT (configs/local.yaml)"
cd "$PROVIDER_ROOT"
exec go run ./cmd/security-provider -config configs/local.yaml
