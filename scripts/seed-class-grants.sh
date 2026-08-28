#!/usr/bin/env bash
# Seed v1.23 class grants for the classification-aware search demo.
#
# Usage:
#   ./scripts/seed-class-grants.sh
#   ./scripts/seed-class-grants.sh --topology-url http://localhost:8084 --tenant demo
#   ./scripts/seed-class-grants.sh --role hr --class PERSONAL --role payroll --class FINANCIAL

set -euo pipefail

TOPOLOGY_URL="${TOPOLOGY_URL:-http://localhost:8084}"
TENANT="${TENANT:-demo}"

declare -a ROLES=()
declare -a CLASSES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --topology-url) TOPOLOGY_URL="$2"; shift 2 ;;
    --tenant) TENANT="$2"; shift 2 ;;
    --role) ROLES+=("$2"); shift 2 ;;
    --class) CLASSES+=("$2"); shift 2 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [[ ${#ROLES[@]} -eq 0 ]]; then
  ROLES=(hr payroll)
  CLASSES=(PERSONAL FINANCIAL)
fi

if [[ ${#ROLES[@]} -ne ${#CLASSES[@]} ]]; then
  echo "Each --role must be paired with --class" >&2
  exit 1
fi

grant_class() {
  local role="$1"
  local class="$2"
  local subject_key
  case "$role" in
    hr) subject_key="4000" ;;
    payroll) subject_key="4100" ;;
    *) subject_key="$role" ;;
  esac
  local idempotency="seed-${subject_key}-${class}-$(date +%s)"
  curl -sf -X POST "${TOPOLOGY_URL}/topology/mutations/class-grant" \
    -H 'Content-Type: application/json' \
    -d "{
      \"tenantId\": \"${TENANT}\",
      \"subjectId\": \"${subject_key}\",
      \"subjectType\": \"GROUP\",
      \"sensitivityClass\": \"${class}\",
      \"permission\": \"SEARCH\",
      \"idempotencyKey\": \"${idempotency}\"
    }" | python3 -m json.tool 2>/dev/null || true
  echo "Granted ${class} (SEARCH) to group ${role} (gid ${subject_key}) on tenant ${TENANT}"
}

echo "Seeding class grants via ${TOPOLOGY_URL} (tenant=${TENANT})..."
for i in "${!ROLES[@]}"; do
  grant_class "${ROLES[$i]}" "${CLASSES[$i]}"
done

echo "Done. Verify with:"
echo "  curl -s '${TOPOLOGY_URL}/topology/tenants/${TENANT}/subjects/user:hr/classes?groups=4000' | python3 -m json.tool"
