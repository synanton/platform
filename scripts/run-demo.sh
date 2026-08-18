#!/usr/bin/env bash
# Brings up the full Synanton demo via docker compose.
# Ontology directory permissions are managed inside a named Docker volume
# by the init-perms service, so no sudo chown is needed on the host.

set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "no .env present - copying .env.example → .env"
  cp .env.example .env
fi

exec docker compose -f deployment/docker/compose.yaml --env-file .env up --build "$@"
