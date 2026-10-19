#!/usr/bin/env bash
# create-registry-secret.sh — per-namespace pull secret for local-registry.
# Replicates the gpu-runtime / speech-to-speech pattern: credentials from
# config/.env (REGISTRY_USER / REGISTRY_PASSWORD), never committed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../config/.env}"
NAMESPACE="${NAMESPACE:-synanton}"
SECRET_NAME="${SECRET_NAME:-local-registry-cred}"
REGISTRY_HOST="${REGISTRY_HOST:-local-registry:5000}"

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE — copy config/.env.example" >&2; exit 1; }
# shellcheck disable=SC1091
. "$ENV_FILE"
: "${REGISTRY_USER:?set REGISTRY_USER in $ENV_FILE}"
: "${REGISTRY_PASSWORD:?set REGISTRY_PASSWORD in $ENV_FILE}"

kubectl -n "$NAMESPACE" create secret docker-registry "$SECRET_NAME" \
    --docker-server="$REGISTRY_HOST" \
    --docker-username="$REGISTRY_USER" \
    --docker-password="$REGISTRY_PASSWORD" \
    --dry-run=client -o yaml | kubectl apply -f -

echo "registry pull secret '$SECRET_NAME' applied in namespace '$NAMESPACE'"
