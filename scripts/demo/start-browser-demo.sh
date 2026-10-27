#!/usr/bin/env bash
set -euo pipefail
PLATFORM="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI="${UI_ROOT:-$PLATFORM/../ui}"
TENANT="${DEMO_TENANT:-demo-v2}"
SECURITY="${SYNANTON_SECURITY_URL:-http://localhost:18081}"
SYNQUEST="${SYNANTON_SYNQUEST_URL:-http://localhost:18083}"
ENV_FILE="$UI/apps/web/.env.local"

check() {
  local name=$1 url=$2
  curl -fsS --max-time 3 "$url" >/dev/null || {
    echo "error: $name not reachable at $url; start the local-real-demo stack first" >&2
    exit 1
  }
}
check security "$SECURITY/actuator/health"
check synquest "$SYNQUEST/health"

# A restarted synquest starts with an empty tenant searcher map: /health is
# ready but /v1/demo/search returns 503 until the projection is loaded.
# Reindex is idempotent; safe even when the searcher is already present.
token=$(curl -fsS --max-time 5 -X POST "$SECURITY/v1/auth/tenant" \
  -H 'Content-Type: application/json' \
  -d "{\"principal_id\":\"demo-runner\",\"tenant_id\":\"$TENANT\",\"capabilities\":[]}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
reindex=$(curl -fsS --max-time 180 -X POST "$SYNQUEST/reindex?tenant=$TENANT" \
  -H "Authorization: Bearer $token")
echo "Reindex: $reindex"

search_status=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST "$SYNQUEST/v1/demo/search" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $token" \
  -d '{"query":"What was the overall response rate of the American Hospital Association Annual Survey for fiscal year 2007?"}')
if [ "$search_status" != "200" ]; then
  echo "ERROR: /v1/demo/search returned $search_status after reindex; not launching Vite" >&2
  exit 1
fi
echo "Search check: 200"

if curl -fsS --max-time 1 http://127.0.0.1:5173 >/dev/null 2>&1; then
  echo "error: port 5173 already in use; stop the existing Vite server first" >&2
  exit 1
fi
cat > "$ENV_FILE" <<EOF
VITE_REAL_API=true
VITE_API_BASE=
VITE_SECURITY_URL=$SECURITY
VITE_SYNQUEST_URL=$SYNQUEST
VITE_DEMO_AUTH_USER=aminin
VITE_DEMO_AUTH_TENANT=$TENANT
EOF
# Vite reads process env before .env.local; force the local targets so a stale
# K8s VITE_SYNQUEST_URL in the shell cannot silently redirect the proxy.
export VITE_REAL_API=true
export VITE_API_BASE=
export VITE_SECURITY_URL="$SECURITY"
export VITE_SYNQUEST_URL="$SYNQUEST"
export VITE_DEMO_AUTH_USER=aminin
export VITE_DEMO_AUTH_TENANT="$TENANT"
trap 'rm -f "$ENV_FILE"' EXIT INT TERM

cat <<EOF
Browser demo: http://127.0.0.1:5173/search
Username: aminin
Tenant: $TENANT (prefilled)
Queries:
  Which medications are listed for substance use disorders?
  IATF 16949
  How does the report's glossary define a major depressive episode?
  In what year was Globex Manufacturing founded and where is it headquartered?
Expected strips:
  BM25 #7 · Dense #2 · RRF #2
  BM25 #1 · Dense — · RRF #1
  BM25 #1 · Dense #4 · RRF #1
  BM25 #3 · Dense #1 · RRF #2
EOF
cd "$UI"
exec pnpm --filter @synanton/web dev --host 127.0.0.1 --port 5173
