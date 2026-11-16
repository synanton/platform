#!/usr/bin/env bash
# Cleanup evaluation-registry state (#728). Ontology/graph orphan deletion is #732.
# Usage:
#   scripts/cleanup-eval-tenants.sh --dry-run [--scope eval|stale-test]
#   scripts/cleanup-eval-tenants.sh --apply --confirm <ids> [--scope eval|stale-test]
set -euo pipefail

NAMESPACE="${NAMESPACE:-synanton}"
CASSANDRA_POD="${CASSANDRA_POD:-cassandra-0}"
AUDIT_DIR="${AUDIT_DIR:-docs/acceptance/provisioning-audit}"
SCOPE="eval"
MODE=""
CONFIRM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) MODE=dry ;;
    --apply) MODE=apply ;;
    --scope) SCOPE="${2:?--scope requires a value}"; shift ;;
    --confirm) CONFIRM="${2:?--confirm requires a value}"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$MODE" ] || { echo "use --dry-run or --apply" >&2; exit 2; }
case "$SCOPE" in eval|stale-test) ;; *) echo "invalid --scope $SCOPE" >&2; exit 2 ;; esac

cql() { kubectl -n "$NAMESPACE" exec "$CASSANDRA_POD" -- cqlsh -e "$1"; }

in_scope() {
  local t="$1"
  if [ "$SCOPE" = "eval" ]; then
    [[ "$t" == eval-* ]]
  else
    [[ "$t" == iso-* || "$t" == slice-* || "$t" = "demo-v2" ]]
  fi
}

rows="$(cql "SELECT bucket, tenant_id FROM ingestion_cache.tenant_registry;" | awk -F'|' 'NF>=2 {gsub(/ /,"",$1); gsub(/ /,"",$2); if ($2 != "" && $2 != "tenant_id") print $1"\t"$2}')"
dry="$(mktemp)"
trap 'rm -f "$dry"' EXIT
while IFS=$'\t' read -r bucket tenant; do
  [ -n "${tenant:-}" ] || continue
  in_scope "$tenant" || continue
  count="$(cql "SELECT count(*) AS c FROM ingestion_cache.source_versions WHERE tenant_id='$tenant' ALLOW FILTERING;" | grep -oE '[0-9]+' | head -1)"
  printf '%s\t%s\t%s\n' "$tenant" "${count:-0}" "$bucket" >> "$dry"
done <<< "$rows"

sort -o "$dry" "$dry"
if [ "$MODE" = "dry" ]; then
  echo "scope=$SCOPE tenants=$(wc -l < "$dry")"
  cat "$dry"
  exit 0
fi

[ -n "$CONFIRM" ] || { echo "--apply requires --confirm <comma-separated tenant ids>" >&2; exit 2; }
expected="$(cut -f1 "$dry" | sort | paste -sd, -)"
if [ "$CONFIRM" != "$expected" ]; then
  echo "refusing: --confirm does not match dry-run scope" >&2
  echo "expected: $expected" >&2
  echo "received: $CONFIRM" >&2
  exit 3
fi
mkdir -p "$AUDIT_DIR"
audit="$AUDIT_DIR/cleanup-$(date -u +%Y%m%d-%H%M%S).json"
provider_table="$(cql "SELECT table_name FROM system_schema.tables WHERE keyspace_name='ingestion_cache' AND table_name='tenant_provider_config';" 2>/dev/null | grep -c tenant_provider_config || true)"
while IFS=$'\t' read -r tenant count bucket; do
  [ -n "${tenant:-}" ] || continue
  in_scope "$tenant" || { echo "refusing out-of-scope tenant $tenant" >&2; exit 4; }
  cql "DELETE FROM ingestion_cache.tenant_registry WHERE bucket=$bucket AND tenant_id='$tenant';" >/dev/null
  if [ "${provider_table:-0}" -gt 0 ]; then
    cql "DELETE FROM ingestion_cache.tenant_provider_config WHERE tenant_id='$tenant';" >/dev/null
  fi
done < "$dry"
python3 - "$audit" "$SCOPE" "$expected" "${USER:-unknown}" "$dry" "${provider_table:-0}" <<'PY'
import json, sys, datetime
audit, scope, confirmed, operator, path, provider_table = sys.argv[1:7]
tenants = []
for line in open(path):
    tenant, count, bucket = line.rstrip('\n').split('\t')
    tenants.append({"tenant_id": tenant, "source_versions": int(count), "bucket": int(bucket)})
json.dump({"scope": scope, "confirmed": confirmed.split(',') if confirmed else [],
           "operator": operator, "deleted_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
           "tenants": tenants, "provider_table_deleted": bool(int(provider_table)),
           "note": "ontology/graph orphan cleanup deferred to #732"},
          open(audit, 'w'), indent=2)
PY
echo "cleaned $(wc -l < "$dry") tenant(s); audit=$audit"
