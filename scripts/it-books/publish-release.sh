#!/usr/bin/env bash
set -euo pipefail

# Publish the IT Books HCL ontology as its own immutable Syntology release.
# This activates only the it-books ontology; Supply Chain active state is untouched.
#
# Required: a running Syntology service and a schema file.
# Auth is optional; set TOKEN when auth is enabled.

SYNTOLOGY_URL="${SYNTOLOGY_URL:-http://localhost:8080}"
TENANT="${TENANT:-demo}"
VERSION="${VERSION:-it-books-v1}"
SCHEMA_PATH="${SCHEMA_PATH:-schemas/it-books/v1/schema.hcl}"
TOKEN="${TOKEN:-}"

if [[ ! -f "$SCHEMA_PATH" ]]; then
  echo "schema not found: $SCHEMA_PATH" >&2
  exit 1
fi

auth=()
if [[ -n "$TOKEN" ]]; then
  auth=(-H "Authorization: Bearer ${TOKEN}")
fi

echo "publishing ${SCHEMA_PATH} as ${VERSION} for tenant ${TENANT}"
curl -sS --fail-with-body \
  -X POST "${SYNTOLOGY_URL}/api/v1/admin/ontology/schemas" \
  -H "X-Tenant-ID: ${TENANT}" \
  "${auth[@]}" \
  -F "file=@${SCHEMA_PATH};type=text/plain" \
  -F "entry=schema.hcl" \
  -F "version=${VERSION}"
echo

echo "resolving active it-books release"
curl -sS --fail-with-body \
  "${SYNTOLOGY_URL}/v1/tenants/${TENANT}/active-ontology-version?ontologyId=it-books" \
  -H "X-Tenant-ID: ${TENANT}" \
  "${auth[@]}"
echo
