#!/usr/bin/env bash
# p1-concurrency.sh — the #292 set-race proof under real concurrency.
#
# Two sources with IDENTICAL bytes are ingested concurrently. The bytes are
# content-addressed: both runs derive the same content ref, and the chunk's
# source_version_ids set must end up holding BOTH source versions (the
# server-side set-append under concurrent writes). Whichever replica picks up
# each run, the result is the same — that is what the two-replica
# anti-affinity makes meaningful.
#
# Prerequisites: the cluster is deployed and run-slice.sh (or an equivalent
# port-forward) provides SYNFLUX_URL + the fixture NFS mount. TOKEN is minted
# from the cluster's JWT secret.
set -euo pipefail

SYNFLUX_URL="${SYNFLUX_URL:-http://localhost:18090}"
TENANT="${TENANT:-slice-a}"
TOKEN="${TOKEN:?export TOKEN (minted from the cluster JWT secret)}"
NAMESPACE="${NAMESPACE:-synanton}"
STAMP="$(date +%s)"
SRC_A="p1-race-a-$STAMP"
SRC_B="p1-race-b-$STAMP"
FIXTURE_NFS_MOUNT="${FIXTURE_NFS_MOUNT:-/mnt/wd6000}"
FIXTURE_NFS_SUBDIR="${FIXTURE_NFS_SUBDIR:-synanton-slice}"
FIXTURE_HOST_DIR="$FIXTURE_NFS_MOUNT/$FIXTURE_NFS_SUBDIR"
RUN_DIR="$FIXTURE_HOST_DIR/race-$STAMP"

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

mkdir -p "$RUN_DIR/$SRC_A" "$RUN_DIR/$SRC_B"
echo "concurrent race fixture $STAMP" > "$RUN_DIR/$SRC_A/doc.md"
cp "$RUN_DIR/$SRC_A/doc.md" "$RUN_DIR/$SRC_B/doc.md"     # identical bytes
SHA=$(sha256sum "$RUN_DIR/$SRC_A/doc.md" | cut -d' ' -f1)
CONTENT_REF=$(python3 -c "
import uuid, sys
print(uuid.uuid5(uuid.UUID('6ba7b810-9dad-11d1-80b4-00c04fd430c8'), sys.argv[1] + ':' + sys.argv[2]))
" "$TENANT" "$SHA")

for src in "$SRC_A" "$SRC_B"; do
    curl -s -X POST "$SYNFLUX_URL/v1/sources" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"source_id\":\"$src\",\"connector_id\":\"slice-conn\",\"connector_type\":\"file\",\"source_endpoint\":\"file:///fixtures/race-$STAMP/$src\",\"capability_flags\":[]}" \
        -o /dev/null -w "created $src: %{http_code}\n"
done

echo "== submitting both runs concurrently"
for src in "$SRC_A" "$SRC_B"; do
    curl -s -X POST "$SYNFLUX_URL/v1/ingestion-runs" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"source_id\":\"$src\",\"resource_id\":\"doc.md\"}" \
        -o "$RUN_DIR/$src.submit.json" -w "submitted $src: %{http_code}\n" &
done
wait

echo "== waiting for both operations"
for src in "$SRC_A" "$SRC_B"; do
    op=$(json "d['operation_id']" < "$RUN_DIR/$src.submit.json")
    for _ in $(seq 1 60); do
        status=$(curl -s "$SYNFLUX_URL/v1/operations/$op" -H "Authorization: Bearer $TOKEN" | json "d['status']")
        case "$status" in SUCCEEDED|FAILED|CANCELLED) break ;; esac
        sleep 2
    done
    echo "$src: $status"
done

echo "== asserting the set-append (both versions on the one content-addressed chunk)"
rows=$(kubectl -n "$NAMESPACE" exec cassandra-0 -- cqlsh -e \
    "SELECT source_version_ids FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT' AND content_ref_id=$CONTENT_REF;" 2>/dev/null)
echo "$rows"
count=$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' <<<"$rows" | sort -u | wc -l)
[ "$count" = "2" ] && echo "PASS both source versions in the set" \
    || { echo "FAIL expected 2 versions, found $count" >&2; exit 1; }
