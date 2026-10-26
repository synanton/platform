#!/usr/bin/env bash
# Local Real Demo v1 (#399): real PDF upload through the public /v1 API, then
# search, provenance, canonical page/section metadata, and idempotent replay.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/deployments/docker"
PDF="$REPO_ROOT/demo-data/documents/mental-health-report-2010.pdf"
EXPECTED_SHA256="d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f"
EXPECTED_PAGE_START=2
EXPECTED_PAGE_END=2
EXPECTED_SECTION_PATH="APPENDIX A: DATA SOURCE DESCRIPTIONS"
# Fixed (not COMPOSE_PROJECT_NAME): reset deletes project-scoped volumes, so
# the name must never collide with the main local stack's project.
PROJECT_NAME="synanton-local-real-demo"
PROFILE_NAME="local-real-demo"
DATA_ROOT="${LOCAL_REAL_DEMO_DATA_ROOT:-$COMPOSE_DIR/data-local-real-demo}"
CONTENT_EXTRACTOR_ROOT="${CONTENT_EXTRACTOR_ROOT:-$REPO_ROOT/../content_extractor}"
EXPECTED_EXTRACTOR_COMMIT="${LOCAL_REAL_DEMO_EXPECTED_EXTRACTOR_COMMIT:-}"
SYNANTON_JWT_SECRET="${SYNANTON_JWT_SECRET:-local-real-demo-secret-at-least-32-bytes}"
SYNFLUX_PORT="${LOCAL_REAL_DEMO_SYNFLUX_PORT:-18090}"
SYNQUEST_PORT="${LOCAL_REAL_DEMO_SYNQUEST_PORT:-18083}"
SECURITY_PORT="${LOCAL_REAL_DEMO_SECURITY_PORT:-18081}"
TENANT="${LOCAL_REAL_DEMO_TENANT:-local-real-demo}"
SOURCE_ID="mental-health-report-2010"
# Must be one of the ingestion service's supported caller-pushed connectors
# (file/filesystem/upload/manual-upload); it is also the ContentRef scheme.
CONNECTOR_ID="manual-upload"
RESOURCE_ID="mental-health-report-2010.pdf"
IDEMPOTENCY_KEY="local-real-demo-mental-health-report-2010-v1"
MAX_WAIT_SECONDS="${LOCAL_REAL_DEMO_MAX_WAIT_SECONDS:-300}"
EVIDENCE_FILE="${LOCAL_REAL_DEMO_EVIDENCE_FILE:-$DATA_ROOT/evidence.json}"
IMAGE_PREFIX="${LOCAL_REAL_DEMO_IMAGE_PREFIX:-synanton-local-real-demo}"
IMAGE_TAG="${LOCAL_REAL_DEMO_IMAGE_TAG:-local}"
SYNFLUX_URL="http://localhost:$SYNFLUX_PORT"
SYNQUEST_URL="http://localhost:$SYNQUEST_PORT"

case "$DATA_ROOT" in
    /*) ;;
    *) DATA_ROOT="$REPO_ROOT/$DATA_ROOT" ;;
esac
case "$CONTENT_EXTRACTOR_ROOT" in
    /*) ;;
    *) CONTENT_EXTRACTOR_ROOT="$REPO_ROOT/$CONTENT_EXTRACTOR_ROOT" ;;
esac
case "$EVIDENCE_FILE" in
    /*) ;;
    *) EVIDENCE_FILE="$REPO_ROOT/$EVIDENCE_FILE" ;;
esac
PLATFORM_COMMIT="${PLATFORM_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
EXTRACTOR_COMMIT="$(git -C "$CONTENT_EXTRACTOR_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"

usage() {
    cat <<'EOF'
Usage: ./scripts/run-local-real-demo.sh [up|test|down|reset]

  up     Start the isolated local-real-demo profile, run acceptance checks,
         and leave it running for inspection (default).
  test   Run acceptance checks against an already-running profile.
  down   Remove this profile's containers/network; preserve all data.
  reset  Remove this profile's containers, named volumes, and its default
         deployments/docker/data-local-real-demo directory.

The fixed Compose project is synanton-local-real-demo and the profile is
local-real-demo. Host ports: synflux 18090, synquest 18083 (override with
LOCAL_REAL_DEMO_SYNFLUX_PORT / LOCAL_REAL_DEMO_SYNQUEST_PORT).

reset removes only this profile's project-scoped volumes and its default
deployments/docker/data-local-real-demo directory. It never removes the main
stack's volumes.

Set CONTENT_EXTRACTOR_ROOT for the sibling checkout and
LOCAL_REAL_DEMO_EXPECTED_EXTRACTOR_COMMIT to assert its commit (CI does both).
EOF
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }
now_ms() { python3 -c 'import time; print(time.time_ns() // 1_000_000)'; }

compose() {
    (cd "$REPO_ROOT" && docker compose \
        --project-name "$PROJECT_NAME" \
        -f "$COMPOSE_DIR/compose.yaml" \
        -f "$COMPOSE_DIR/compose.local-real-demo.yaml" \
        --profile local-real-demo "$@")
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

mint_token() {
    python3 - "$TENANT" "$SYNANTON_JWT_SECRET" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time

def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

tenant, secret = sys.argv[1:]
header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
claims = b64url(json.dumps({
    "sub": "local-real-demo",
    "tenant_id": tenant,
    "exp": int(time.time()) + 3600,
}).encode())
signing_input = f"{header}.{claims}".encode()
signature = b64url(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
print(f"{header}.{claims}.{signature}")
PY
}

http() {
    local method="$1" url="$2" token="$3" body="${4:-}" tmp code
    tmp="$(mktemp)"
    if [ -n "$body" ]; then
        code="$(curl -sS --max-time 60 -o "$tmp" -w '%{http_code}' -X "$method" "$url" \
            -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
            -d "$body" || true)"
    else
        code="$(curl -sS --max-time 60 -o "$tmp" -w '%{http_code}' -X "$method" "$url" \
            -H "Authorization: Bearer $token" || true)"
    fi
    printf '%s\n' "${code:-000}"
    python3 - "$tmp" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).read_text())
PY
    rm -f "$tmp"
}

http_multipart() {
    local token="$1" tmp code
    tmp="$(mktemp)"
    code="$(curl -sS --max-time 60 -o "$tmp" -w '%{http_code}' \
        -H "Authorization: Bearer $token" \
        -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
        -F "connector_id=$CONNECTOR_ID" \
        -F "source_id=$SOURCE_ID" \
        -F "resource_id=$RESOURCE_ID" \
        -F "file=@$PDF;type=application/pdf" \
        "$SYNFLUX_URL/v1/ingestion-runs" || true)"
    printf '%s\n' "${code:-000}"
    python3 - "$tmp" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).read_text())
PY
    rm -f "$tmp"
}

status_of() { printf '%s' "${1%%$'\n'*}"; }
body_of() { printf '%s' "${1#*$'\n'}"; }
json_value() {
    python3 - "$1" "$2" <<'PY'
import json
import sys
value = json.loads(sys.argv[1])
for key in sys.argv[2].split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print("" if value is None else value)
PY
}

assert_status() {
    local label="$1" response="$2" expected="$3" actual
    actual="$(status_of "$response")"
    [ "$actual" = "$expected" ] || fail "$label: expected HTTP $expected, got $actual ($(body_of "$response"))"
    pass "$label (HTTP $expected)"
}

wait_health() {
    local name="$1" url="$2" token="$3" deadline=$((SECONDS + 300))
    while (( SECONDS < deadline )); do
        if curl -fsS --max-time 3 -H "Authorization: Bearer $token" \
            "$url/health" >/dev/null 2>&1; then
            pass "$name is healthy"
            return 0
        fi
        sleep 3
    done
    compose logs --tail=80 "$name" >&2 || true
    fail "$name did not become healthy within 300 seconds"
}

wait_service_health() {
    local service="$1" deadline=$((SECONDS + 300)) id state
    while (( SECONDS < deadline )); do
        id="$(compose ps -q "$service" 2>/dev/null | head -1)"
        if [ -n "$id" ]; then
            state="$(docker inspect -f \
                '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                "$id" 2>/dev/null || true)"
            case "$state" in
                healthy|running)
                    pass "$service is $state"
                    return 0
                    ;;
            esac
        fi
        sleep 3
    done
    compose logs --tail=80 "$service" >&2 || true
    fail "$service did not become healthy within 300 seconds"
}

build_images() {
    local stage
    printf 'Building host boot jars and demo images...\n'
    "$REPO_ROOT/gradlew" -q -p "$REPO_ROOT" \
        :java:synflux:bootJar :java:synquest:bootJar :java:security:bootJar -x test
    "$CONTENT_EXTRACTOR_ROOT/gradlew" -q -p "$CONTENT_EXTRACTOR_ROOT" \
        :java:extraction-gateway:bootJar -x test
    stage="$(mktemp -d)"
    mkdir -p "$stage/java/synflux/build/libs" \
        "$stage/java/synquest/build/libs" \
        "$stage/java/security/build/libs" \
        "$stage/java/extraction-gateway/build/libs"
    cp "$REPO_ROOT"/java/synflux/build/libs/synflux*.jar "$stage/java/synflux/build/libs/"
    cp "$REPO_ROOT"/java/synquest/build/libs/synquest*.jar "$stage/java/synquest/build/libs/"
    cp "$REPO_ROOT"/java/security/build/libs/security*.jar "$stage/java/security/build/libs/"
    cp "$CONTENT_EXTRACTOR_ROOT"/java/extraction-gateway/build/libs/extraction-gateway*.jar \
        "$stage/java/extraction-gateway/build/libs/"
    docker build -q -f "$COMPOSE_DIR/synflux.prebuilt.Dockerfile" \
        -t "$IMAGE_PREFIX/synflux:$IMAGE_TAG" "$stage" >/dev/null
    docker build -q -f "$COMPOSE_DIR/synquest.prebuilt.Dockerfile" \
        -t "$IMAGE_PREFIX/synquest:$IMAGE_TAG" "$stage" >/dev/null
    docker build -q -f "$COMPOSE_DIR/security.prebuilt.Dockerfile" \
        -t "$IMAGE_PREFIX/security:$IMAGE_TAG" "$stage" >/dev/null
    docker build -q -f "$COMPOSE_DIR/extraction-gateway.prebuilt.Dockerfile" \
        -t "$IMAGE_PREFIX/extraction-gateway:$IMAGE_TAG" "$stage" >/dev/null
    rm -rf "$stage"
    pass "demo images built ($IMAGE_PREFIX/{synflux,synquest,security,extraction-gateway}:$IMAGE_TAG)"
}

preflight() {
    local actual_sha
    for command in docker curl python3 sha256sum; do require_command "$command"; done
    [ -f "$PDF" ] || fail "pinned PDF is missing: $PDF"
    actual_sha="$(sha256sum "$PDF" | cut -d' ' -f1)"
    [ "$actual_sha" = "$EXPECTED_SHA256" ] || fail "fixture SHA-256 mismatch: $actual_sha"
    [ -d "$CONTENT_EXTRACTOR_ROOT" ] || fail "content_extractor checkout is missing: $CONTENT_EXTRACTOR_ROOT"
    [ -f "$CONTENT_EXTRACTOR_ROOT/deployment/docker/extraction-gateway.Dockerfile" ] \
        || fail "content_extractor Dockerfile not found below $CONTENT_EXTRACTOR_ROOT"
    if [ -n "$EXPECTED_EXTRACTOR_COMMIT" ] && [ "$EXTRACTOR_COMMIT" != "$EXPECTED_EXTRACTOR_COMMIT" ]; then
        fail "content_extractor is at $EXTRACTOR_COMMIT, expected $EXPECTED_EXTRACTOR_COMMIT"
    fi
    [[ "$TENANT" =~ ^[a-zA-Z0-9._-]+$ ]] || fail "tenant must contain only letters, digits, '.', '_' or '-'"
    [ "${#SYNANTON_JWT_SECRET}" -ge 32 ] || fail "SYNANTON_JWT_SECRET must be at least 32 characters"
    case "$SYNANTON_JWT_SECRET" in
        *placeholder*|replace-me*) fail "SYNANTON_JWT_SECRET is a placeholder" ;;
    esac
    pass "pinned 15-page PDF fixture and required dependencies validated"
}

run_acceptance() {
    local token response source_response submit_response run_id operation_id
    local repeated_response repeated_operation versions version_id digest
    local operation status outcome deadline search_response hit_json object_id provenance_ref
    local provenance_response source_uri chunk_sha256 content_ref ordinal page_rows page_evidence attempt
    local upload_started_ms operation_completed_ms search_completed_ms
    local ingest_operation_ms ingest_to_searchable_ms search_latencies search_p50_ms
    local marker='overall response rate for fiscal year 2007 was 78 percent'
    local search_samples="${LOCAL_REAL_DEMO_SEARCH_SAMPLES:-10}"

    preflight
    token="$(mint_token)"
    for service in postgres cassandra minio extraction-gateway security synflux synquest; do
        wait_service_health "$service"
    done
    wait_health synflux "$SYNFLUX_URL" "$token"
    wait_health synquest "$SYNQUEST_URL" "$token"

    for attempt in $(seq 1 15); do
        source_response="$(http POST "$SYNFLUX_URL/v1/sources" "$token" \
            "{\"source_id\":\"$SOURCE_ID\",\"connector_id\":\"$CONNECTOR_ID\",\"connector_type\":\"upload\",\"source_endpoint\":\"upload://$CONNECTOR_ID\",\"capability_flags\":[]}")"
        case "$(status_of "$source_response")" in
            201) break ;;
            000|500|502|503) sleep 2 ;;
            *) break ;;
        esac
    done
    assert_status 'create upload source through /v1/sources' "$source_response" 201

    upload_started_ms="$(now_ms)"
    submit_response="$(http_multipart "$token")"
    assert_status 'upload real PDF through multipart /v1/ingestion-runs' "$submit_response" 202
    run_id="$(json_value "$(body_of "$submit_response")" run_id)"
    operation_id="$(json_value "$(body_of "$submit_response")" operation_id)"
    [ -n "$run_id" ] && [ -n "$operation_id" ] || fail 'run submission omitted run_id or operation_id'

    printf 'Waiting for operation %s' "$operation_id"
    deadline=$((SECONDS + MAX_WAIT_SECONDS))
    while (( SECONDS < deadline )); do
        response="$(http GET "$SYNFLUX_URL/v1/operations/$operation_id" "$token")"
        [ "$(status_of "$response")" = 200 ] || { sleep 2; continue; }
        operation="$(body_of "$response")"
        status="$(json_value "$operation" status)"
        case "$status" in
            SUCCEEDED) break ;;
            FAILED|CANCELLED|EXPIRED) fail "ingestion operation ended $status: $operation" ;;
        esac
        printf '.'
        sleep 2
    done
    printf '\n'
    if [ "${status:-}" != SUCCEEDED ]; then
        compose logs --tail=80 synflux >&2 || true
        fail "operation $operation_id did not complete in ${MAX_WAIT_SECONDS}s"
    fi
    outcome="$(json_value "$operation" outcome)"
    case "$outcome" in
        FULL|PARTIAL) ;;
        *) fail "unexpected ingestion outcome '$outcome': $operation" ;;
    esac
    operation_completed_ms="$(now_ms)"
    ingest_operation_ms=$((operation_completed_ms - upload_started_ms))
    pass "PDF ingestion completed successfully (run $run_id, outcome $outcome, ${ingest_operation_ms}ms)"

    versions="$(http GET "$SYNFLUX_URL/v1/sources/$SOURCE_ID/versions" "$token")"
    assert_status 'read source versions' "$versions" 200
    versions="$(body_of "$versions")"
    [ "$(python3 -c 'import json,sys; print(len(json.loads(sys.argv[1])))' "$versions")" = 1 ] \
        || fail 'expected exactly one source version after first ingestion'
    version_id="$(json_value "$versions" 0.version_id)"
    digest="$(json_value "$versions" 0.content_digest)"
    digest="${digest#sha256:}"
    [ "$digest" = "$EXPECTED_SHA256" ] || fail "source version digest mismatch: $digest"
    pass "source version is bound to the pinned PDF bytes ($version_id)"

    # A recreating Cassandra can leave synquest's driver briefly without a
    # node (observed after `up` recreated the database while synquest stayed
    # up). Rebuild is idempotent, so retry the transient 500/503 window.
    for attempt in $(seq 1 15); do
        response="$(http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$token" '{}')"
        case "$(status_of "$response")" in
            200) break ;;
            000|500|503) sleep 2 ;;
            *) break ;;
        esac
    done
    assert_status 'build search projection' "$response" 200
    search_response="$(http POST "$SYNQUEST_URL/v1/search" "$token" \
        "{\"query\":\"$marker\"}")"
    assert_status 'search the extracted PDF fact' "$search_response" 200
    hit_json="$(python3 - "$(body_of "$search_response")" <<'PY'
import json
import sys
body = json.loads(sys.argv[1])
if not body.get("results"):
    raise SystemExit("search returned no results")
print(json.dumps(body["results"][0]))
PY
)" || fail "search returned no results: $(body_of "$search_response")"
    object_id="$(json_value "$hit_json" object_id)"
    provenance_ref="$(json_value "$hit_json" provenance_ref)"
    [ -n "$object_id" ] && [ -n "$provenance_ref" ] || fail 'search hit omitted canonical object_id/provenance_ref'
    case "$provenance_ref" in /v1/knowledge/*/provenance) ;; *) fail "unexpected provenance_ref: $provenance_ref" ;; esac
    search_completed_ms="$(now_ms)"
    ingest_to_searchable_ms=$((search_completed_ms - upload_started_ms))
    pass "search found known table fact in canonical chunk $object_id (searchable ${ingest_to_searchable_ms}ms after upload)"

    search_latencies=""
    for _ in $(seq 1 "$search_samples"); do
        local sample_start sample_end
        sample_start="$(now_ms)"
        http POST "$SYNQUEST_URL/v1/search" "$token" "{\"query\":\"$marker\"}" >/dev/null
        sample_end="$(now_ms)"
        search_latencies+="$((sample_end - sample_start)) "
    done
    search_p50_ms="$(python3 - "$search_latencies" <<'PY'
import statistics
import sys
values = sorted(int(value) for value in sys.argv[1].split())
print(int(statistics.median(values)))
PY
)"
    pass "search p50 over $search_samples calls: ${search_p50_ms}ms"

    provenance_response="$(http GET "$SYNQUEST_URL$provenance_ref" "$token")"
    assert_status 'resolve the live provenance_ref' "$provenance_response" 200
    provenance_response="$(body_of "$provenance_response")"
    python3 - "$provenance_response" "$version_id" "$EXPECTED_SHA256" <<'PY'
import json
import sys
provenance = json.loads(sys.argv[1])
assert sys.argv[2] in provenance["source_version_ids"], "source version missing from provenance"
assert provenance["source_sha256"] == sys.argv[3], "provenance digest mismatch"
assert provenance.get("content_cache_reference"), "content cache reference missing"
PY
    source_uri="$(json_value "$provenance_response" source_uri)"
    chunk_sha256="$(json_value "$provenance_response" chunk_sha256)"
    pass 'provenance references the ingested version, original bytes, and durable content artifact'

    content_ref="${object_id%%:*}"
    ordinal="${object_id##*:}"
    [[ "$content_ref" =~ ^[0-9a-fA-F-]{36}$ && "$ordinal" =~ ^[0-9]+$ ]] \
        || fail "unexpected canonical chunk object_id: $object_id"
    page_rows="$(compose exec -T cassandra cqlsh -e \
        "SELECT chunk_ordinal,page_start,page_end,section_path FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT' AND content_ref_id=$content_ref;" 2>/dev/null)" \
        || fail 'could not read canonical chunk page/section metadata from Cassandra'
    chunk_text="$(compose exec -T cassandra cqlsh -e \
        "SELECT chunk_text FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT' AND content_ref_id=$content_ref AND chunk_ordinal=$ordinal;" 2>/dev/null)" \
        || fail 'could not read the canonical search-hit chunk text from Cassandra'
    python3 - "$chunk_text" "$marker" <<'PY'
import re
import sys
normalize = lambda value: re.sub(r"\s+", " ", value).casefold()
if normalize(sys.argv[2]) not in normalize(sys.argv[1]):
    raise SystemExit("search hit chunk does not contain the known report fact")
PY
    pass 'canonical search-hit chunk contains the known fiscal-year response-rate fact'
    page_evidence="$(python3 - "$page_rows" "$ordinal" \
        "$EXPECTED_PAGE_START" "$EXPECTED_PAGE_END" "$EXPECTED_SECTION_PATH" <<'PY'
import json
import sys

rows = []
for line in sys.argv[1].splitlines():
    fields = [field.strip() for field in line.split("|")]
    if len(fields) != 4 or not fields[0].isdigit():
        continue
    rows.append({
        "chunk_ordinal": int(fields[0]),
        "page_start": int(fields[1]),
        "page_end": int(fields[2]),
        "section_path": fields[3],
    })
match = next((row for row in rows if row["chunk_ordinal"] == int(sys.argv[2])), None)
expected_start, expected_end, expected_section = int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
if match is None:
    raise SystemExit("search hit chunk is absent from canonical storage")
# page_start/page_end are inclusive: a single-page chunk has start == end.
if (match["page_start"], match["page_end"]) != (expected_start, expected_end):
    raise SystemExit(
        f"unexpected canonical page range {match['page_start']}-{match['page_end']}; "
        f"expected {expected_start}-{expected_end}"
    )
if match["section_path"] != expected_section:
    raise SystemExit(
        f"unexpected canonical section path {match['section_path']!r}; expected {expected_section!r}"
    )
print(json.dumps(match))
PY
)" || fail 'canonical chunk page/section metadata assertions failed'
    pass "canonical chunk pins page range and section metadata (inclusive): $page_evidence"

    repeated_response="$(http_multipart "$token")"
    assert_status 'repeat identical upload with the same Idempotency-Key' "$repeated_response" 202
    repeated_operation="$(json_value "$(body_of "$repeated_response")" operation_id)"
    [ "$repeated_operation" = "$operation_id" ] \
        || fail "idempotent replay changed operation id ($operation_id -> $repeated_operation)"
    versions="$(http GET "$SYNFLUX_URL/v1/sources/$SOURCE_ID/versions" "$token")"
    [ "$(python3 -c 'import json,sys; print(len(json.loads(sys.argv[1])))' "$(body_of "$versions")")" = 1 ] \
        || fail 'idempotent replay created a duplicate source version'
    pass "repeat upload resolved to the same operation and exactly one source version ($repeated_operation)"

    repeated_search="$(http POST "$SYNQUEST_URL/v1/search" "$token" "{\"query\":\"$marker\"}")"
    assert_status 'repeat search after idempotent replay' "$repeated_search" 200
    repeated_hit="$(json_value "$(body_of "$repeated_search")" results.0.object_id)"
    [ "$repeated_hit" = "$object_id" ] \
        || fail "search results changed after idempotent replay ($object_id -> $repeated_hit)"
    pass "repeat search returns the same canonical hit after replay ($repeated_hit)"

    mkdir -p "$(dirname "$EVIDENCE_FILE")"
    python3 - "$EVIDENCE_FILE" "$MODE" "$PLATFORM_COMMIT" "$EXTRACTOR_COMMIT" \
        "$PROJECT_NAME" "$PROFILE_NAME" "$IMAGE_PREFIX" "$IMAGE_TAG" "$TENANT" \
        "$SOURCE_ID" "$run_id" "$operation_id" "$outcome" "$version_id" \
        "$EXPECTED_SHA256" "$object_id" "$page_evidence" "$source_uri" "$chunk_sha256" \
        "$ingest_operation_ms" "$ingest_to_searchable_ms" "$search_p50_ms" "$search_samples" <<'PY'
import datetime
import json
import pathlib
import sys

(path, mode, platform_commit, extractor_commit, project, profile, image_prefix,
 image_tag, tenant, source_id, run_id, operation_id, outcome, version_id, digest,
 object_id, page, source_uri, chunk_sha256, operation_ms, searchable_ms,
 search_p50_ms, search_samples) = sys.argv[1:]
evidence = {
    "command": f"./scripts/run-local-real-demo.sh {mode}",
    "captured_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "platform_commit": platform_commit,
    "extractor_commit": extractor_commit,
    "compose_project": project,
    "profile": profile,
    "image_tag": f"{image_prefix}/{{synflux,synquest,extraction-gateway}}:{image_tag}",
    "tenant": tenant,
    "source_id": source_id,
    "run_id": run_id,
    "operation_id": operation_id,
    "operation_outcome": outcome,
    "source_version_id": version_id,
    "fixture_sha256": digest,
    "search_hit_object_id": object_id,
    "search_hit_source_uri": source_uri,
    "search_hit_chunk_sha256": chunk_sha256,
    "canonical_chunk_page_metadata": json.loads(page),
    "page_range_semantics": "inclusive (single page has start == end)",
    "latency": {
        "ingest_operation_ms": int(operation_ms),
        "ingest_to_searchable_ms": int(searchable_ms),
        "search_p50_ms": int(search_p50_ms),
        "search_samples": int(search_samples),
    },
    "idempotency": "same operation_id; one source version after replay",
}
pathlib.Path(path).write_text(json.dumps(evidence, indent=2) + "\n")
print(f"Evidence written to {path}")
PY
    pass 'Local Real Demo v1 acceptance checks passed'
}

action="${1:-up}"
case "$action" in
    -h|--help|help) usage ;;
    up)
        require_command docker
        preflight
        [ -x "$REPO_ROOT/gradlew" ] || fail "missing executable $REPO_ROOT/gradlew"
        [ -x "$CONTENT_EXTRACTOR_ROOT/gradlew" ] \
            || fail "missing executable $CONTENT_EXTRACTOR_ROOT/gradlew"
        mkdir -p "$DATA_ROOT/minio" "$DATA_ROOT/postgres" "$DATA_ROOT/cassandra" "$DATA_ROOT/synquest"
        # MinIO runs as uid 65532 and synquest as uid 100; bind mounts replace
        # the image's owned directory, so both roots must be writable by them.
        chmod 0777 "$DATA_ROOT/minio" "$DATA_ROOT/synquest"
        export DATA_ROOT MINIO_DATA_DIR="$DATA_ROOT/minio"
        export CONTENT_EXTRACTOR_ROOT SYNANTON_JWT_SECRET
        export IMAGE_PREFIX IMAGE_TAG
        build_images
        # Force recreation keeps the app containers from holding stale driver
        # connections when a pinned database image changes underneath them.
        # Named volumes and host data roots still preserve all ingested state.
        compose up -d --no-build --force-recreate postgres cassandra minio minio-init \
            extraction-gateway security synflux synquest
        MODE=up
        run_acceptance
        ;;
    test)
        export DATA_ROOT MINIO_DATA_DIR="$DATA_ROOT/minio"
        export CONTENT_EXTRACTOR_ROOT SYNANTON_JWT_SECRET
        export IMAGE_PREFIX IMAGE_TAG
        MODE=test
        run_acceptance
        ;;
    validate)
        preflight
        ;;
    down)
        compose down
        pass "containers stopped; data preserved at $DATA_ROOT"
        ;;
    reset)
        require_command docker
        compose down --volumes
        default_data_root="$COMPOSE_DIR/data-local-real-demo"
        if [ "$DATA_ROOT" != "$default_data_root" ]; then
            fail "refusing to delete custom data root '$DATA_ROOT'; remove it explicitly after inspecting it"
        fi
        if [ -d "$default_data_root" ]; then
            # Cassandra/MinIO write as their container users; a root helper is
            # the only portable way to clear those files without host sudo.
            docker run --rm -v "$default_data_root:/data" \
                alpine@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a \
                sh -c 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +'
            rmdir "$default_data_root" \
                || fail "data root not empty after cleanup: $default_data_root"
        fi
        pass 'local-real-demo containers, named volumes, and isolated default data removed'
        ;;
    *) usage >&2; fail "unknown command: $action" ;;
esac
