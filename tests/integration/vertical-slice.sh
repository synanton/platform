#!/usr/bin/env bash
# SNTP-033 vertical-slice acceptance run (#253) — one command, five criteria,
# non-zero exit on the first failure.
#
#   tests/integration/vertical-slice.sh            # M70q process profile
#   WORKER_PROFILE=k8s tests/integration/vertical-slice.sh
#
# Criteria (in order):
#   1. Ingest a known source → immutable version (operation SUCCEEDED, outcome FULL)
#   2. Content artifact retrievable → provenance_ref resolves to the version
#      and the Content Cache reference; digest matches the fixture bytes
#   3. Restart recovery → worker crashed after-manifest (SYNANTON_FAULT_INJECT,
#      the documented hook), restart clean, operation SUCCEEDED, exactly one
#      SourceVersion — no duplicates
#   4. Search → correct hit through the public API: object_id, provenance_ref
#      resolves, metadata.strategy == "bm25", source_version_ids contains the
#      version; configuration_generation increases on rebuild
#   5. Cross-tenant denial → tenant B sees zero results, body tenant ignored,
#      provenance 404
#
# Plus: exactly one SourcePublished event after recovery (the event rides
# inline on the single version row, deduped by IF NOT EXISTS — a duplicate
# recovery pass cannot fork it; requires the Kafka adapter up for the
# published flag to flip).
#
# Worker-profile status: compose exercised on M70q; the k8s profile is
# UNTESTED — the first cluster run exercises it.
#
# Not asserted here, deliberately: outcome PARTIAL / quarantined resources —
# the Content Cache integrity exceptions that produce QUARANTINED runs are
# internal-corruption class with no honest data-driven generator in the slice;
# the derivation is unit-proven and the generator is #294's scope. The script
# grows the assertion when a generator lands — it does not fake one.
#
# The script is environment-parameterized, not duplicated:
#   WORKER_PROFILE  compose (default) | process | k8s
#   COMPOSE_FILE    default deployment/docker/compose.yaml
#   SYNFLUX_URL     default http://localhost:8090 (compose maps 8090)
#   SYNQUEST_URL    default http://localhost:8083 (compose maps 8083)
#   SYNANTON_JWT_SECRET   shared HS256 secret of both planes
#   FIXTURE_ROOT    directory under the worker's SYNFLUX_FIXTURE_ROOT
#   K8S_NAMESPACE   k8s profile only
set -euo pipefail

# Dev secret hygiene: source .env at the repo root when present, so the
# secret never needs to appear in shell history or CI logs.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$SCRIPT_DIR/../../.env" ] && . "$SCRIPT_DIR/../../.env"

SYNFLUX_URL="${SYNFLUX_URL:-http://localhost:8090}"
SYNQUEST_URL="${SYNQUEST_URL:-http://localhost:8083}"
SYNANTON_JWT_SECRET="${SYNANTON_JWT_SECRET:?SYNANTON_JWT_SECRET must be set (both planes verify with it)}"
TENANT_A="${TENANT_A:-slice-a}"
TENANT_B="${TENANT_B:-slice-b}"
FIXTURE_ROOT="${FIXTURE_ROOT:?FIXTURE_ROOT must be set — the worker-visible fixture root (SYNFLUX_FIXTURE_ROOT)}"
FIXTURE_HOST_DIR="${FIXTURE_HOST_DIR:-$FIXTURE_ROOT}"
WORKER_PROFILE="${WORKER_PROFILE:-compose}"
K8S_NAMESPACE="${K8S_NAMESPACE:-synanton}"
CASSANDRA_POD="${CASSANDRA_POD:-cassandra-0}"
COMPOSE_FILE="${COMPOSE_FILE:-deployment/docker/compose.yaml}"
STAMP="$(date +%s)"
SOURCE_MAIN="slice-main-$STAMP"
SOURCE_RECOVERY="slice-recovery-$STAMP"

step() { printf '\n=== %s ===\n' "$*"; }
pass() { printf '  PASS %s\n' "$*"; }
die()  { printf '  FAIL %s\n' "$*" >&2; exit 1; }

assert_eq() { [ "$2" = "$3" ] && pass "$1" || die "$1 (expected '$3', got '$2')"; }
assert_not_empty() { [ -n "$2" ] && pass "$1" || die "$1 (empty)"; }

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

mint_token() {  # mint_token <tenant>
python3 - "$1" "$SYNANTON_JWT_SECRET" <<'PYEOF'
import sys, json, hmac, hashlib, base64, time

def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

tenant, secret = sys.argv[1], sys.argv[2]
header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
claims = b64url(json.dumps({
    "sub": "slice-operator", "tenant_id": tenant,
    "exp": int(time.time()) + 3600,
}).encode())
signing = f"{header}.{claims}".encode()
sig = b64url(hmac.new(secret.encode(), signing, hashlib.sha256).digest())
print(f"{header}.{claims}.{sig}")
PYEOF
}

http() {  # http <method> <url> <token> [body] -> <status>\n<response-body>
    # Connection-refused yields status 000 (curl exit 7) — a retryable
    # condition for the pollers, never a script abort under set -e.
    local method="$1" url="$2" token="$3" body="${4:-}" tmp code
    tmp=$(mktemp)
    if [ -n "$body" ]; then
        code=$(curl -s -o "$tmp" -w '%{http_code}' -X "$method" "$url" \
            -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
            -H 'Idempotency-Key: '"$STAMP-$RANDOM" -d "$body" || true)
    else
        code=$(curl -s -o "$tmp" -w '%{http_code}' -X "$method" "$url" \
            -H "Authorization: Bearer $token" || true)
    fi
    printf '%s\n%s' "${code:-000}" "$(cat "$tmp")"
    rm -f "$tmp"
}

# Body of the two-line http() output: status line then response.
status_of() { sed -n '1p' <<<"$1"; }
body_of()   { sed -n '2p' <<<"$1"; }

wait_health() {  # wait_health <name> <url>
    local name="$1" url="$2" i
    for i in $(seq 1 60); do
        [ "$(status_of "$(http GET "$url/health" "$TOK_A")")" = "200" ] && { pass "$name healthy"; return 0; }
        sleep 2
    done
    die "$name not healthy after 120s"
}

wait_operation() {  # wait_operation <op_id> -> final operation JSON
    local op_id="$1" i out
    for i in $(seq 1 90); do
        out=$(body_of "$(http GET "$SYNFLUX_URL/v1/operations/$op_id" "$TOK_A")")
        case "$(json "d['status']" <<<"$out" 2>/dev/null || echo '')" in
            SUCCEEDED|FAILED|CANCELLED) printf '%s' "$out"; return 0 ;;
        esac
        sleep 2
    done
    die "operation $op_id did not reach a terminal state in 180s"
}

cqlsh_query() {  # cqlsh_query <cql> -> query output (empty when unavailable)
    case "$WORKER_PROFILE" in
        compose) docker compose -f "$COMPOSE_FILE" exec -T cassandra cqlsh -e "$1" 2>/dev/null ;;
        k8s)     kubectl -n "$K8S_NAMESPACE" exec "$CASSANDRA_POD" -- cqlsh -e "$1" 2>/dev/null ;;
        process) command -v cqlsh >/dev/null 2>&1 && cqlsh -e "$1" 2>/dev/null ;;
    esac
}

start_worker() {  # start_worker [fault-point]
    case "$WORKER_PROFILE" in
        compose)
            # The fault env is baked into the recreated container; the injected
            # crash is one-shot per process, and the clean recreate below drops it.
            if [ -n "${1:-}" ]; then
                (cd deployment/docker && SYNANTON_FAULT_INJECT="$1" docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate synflux)
            else
                (cd deployment/docker && docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate synflux)
            fi
            ;;
        process)
            if [ -n "${1:-}" ]; then
                SYNANTON_FAULT_INJECT="$1" SYNFLUX_FIXTURE_ROOT="$FIXTURE_ROOT" \
                    ./gradlew :java:synflux:bootRun > /tmp/synflux-slice.log 2>&1 &
            else
                SYNFLUX_FIXTURE_ROOT="$FIXTURE_ROOT" \
                    ./gradlew :java:synflux:bootRun > /tmp/synflux-slice.log 2>&1 &
            fi
            WORKER_PID=$!
            ;;
        k8s)
            if [ -n "${1:-}" ]; then
                kubectl -n "$K8S_NAMESPACE" set env deploy/synflux "SYNANTON_FAULT_INJECT=$1"
            else
                kubectl -n "$K8S_NAMESPACE" set env deploy/synflux "SYNANTON_FAULT_INJECT-"
            fi
            kubectl -n "$K8S_NAMESPACE" rollout restart deploy/synflux
            kubectl -n "$K8S_NAMESPACE" rollout status deploy/synflux --timeout=180s
            ;;
        *) die "unknown WORKER_PROFILE $WORKER_PROFILE — compose, process or k8s" ;;
    esac
}

worker_crashed() {  # worker_crashed -> 0 when the injected crash was observed
    case "$WORKER_PROFILE" in
        compose)
            docker compose -f "$COMPOSE_FILE" logs --since 5m synflux 2>/dev/null | grep -q 'FAULT INJECTED'
            ;;
        process)
            ! kill -0 "${WORKER_PID:-0}" 2>/dev/null
            ;;
        k8s)
            # Pod restarts rotate container logs — the durable signal is the
            # last terminated container's exit code 70, not log text.
            [ "$(kubectl -n "$K8S_NAMESPACE" get pods -l app=synflux -o jsonpath='{.items[0].status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null)" = "70" ] \
                || kubectl -n "$K8S_NAMESPACE" logs deploy/synflux --since=5m 2>/dev/null | grep -q 'FAULT INJECTED'
            ;;
    esac
}

stop_worker() {
    case "$WORKER_PROFILE" in
        compose) docker compose -f "$COMPOSE_FILE" stop synflux ;;
        process)
            [ -n "${WORKER_PID:-}" ] && kill "$WORKER_PID" 2>/dev/null || true
            pkill -f 'org.synanton.SynfluxApplication' 2>/dev/null || true
            pkill -f 'SynfluxApplication' 2>/dev/null || true
            sleep 2
            ;;
        k8s) kubectl -n "$K8S_NAMESPACE" scale deploy/synflux --replicas=0
             kubectl -n "$K8S_NAMESPACE" scale deploy/synflux --replicas=1 ;;
    esac
}

# The injected crash is one-shot per process — restart happens WITHOUT the
# env var, so recovery resumes from the checkpoint instead of crash-looping.

step "Setup"
TOK_A=$(mint_token "$TENANT_A")
TOK_B=$(mint_token "$TENANT_B")
mkdir -p "$FIXTURE_HOST_DIR/$SOURCE_MAIN" "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY"
TOKEN_MARK="zebra-slice-token-$STAMP"
printf 'Supply-chain policy %s.\nThe logistics clause governs freight.\n' "$TOKEN_MARK" \
    > "$FIXTURE_HOST_DIR/$SOURCE_MAIN/fixture-doc.md"
printf 'Recovery fixture %s.\nThe restart clause governs resumption.\n' "$TOKEN_MARK" \
    > "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY/recovery-doc.md"
FIXTURE_HOST_DIR="$(cd "$FIXTURE_HOST_DIR" && pwd)"
FIXTURE_SHA=$(sha256sum "$FIXTURE_HOST_DIR/$SOURCE_MAIN/fixture-doc.md" | cut -d' ' -f1)
RECOVERY_SHA=$(sha256sum "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY/recovery-doc.md" | cut -d' ' -f1)
# Compose profile rebuilds synflux in start_worker; synquest needs one
# explicit rebuild — the stale image predates the provenance endpoint and
# would fail criterion 2 with a misleading 404.
if [ "$WORKER_PROFILE" = "compose" ]; then
    (cd deployment/docker && docker compose -f "$COMPOSE_FILE" up -d --build synquest)
fi
wait_health synflux "$SYNFLUX_URL"
wait_health synquest "$SYNQUEST_URL"

step "Criterion 1 — ingest creates an immutable version"
out=$(http POST "$SYNFLUX_URL/v1/sources" "$TOK_A" "{\"source_id\":\"$SOURCE_MAIN\",\"connector_id\":\"slice-conn\",\"connector_type\":\"file\",\"source_endpoint\":\"file://$FIXTURE_ROOT/$SOURCE_MAIN\",\"capability_flags\":[]}")
[ "$(status_of "$out")" = "201" ] || die "source creation failed: $(body_of "$out")"
pass "source created"

out=$(http POST "$SYNFLUX_URL/v1/ingestion-runs" "$TOK_A" \
    "{\"source_id\":\"$SOURCE_MAIN\",\"resource_id\":\"fixture-doc.md\"}")
[ "$(status_of "$out")" = "202" ] || die "run submission failed: $(body_of "$out")"
RUN_ID=$(json "d['run_id']" <<<"$(body_of "$out")")
OP_ID=$(json "d['operation_id']" <<<"$(body_of "$out")")
assert_not_empty "run submitted — run_id $RUN_ID" "$RUN_ID"

op=$(wait_operation "$OP_ID")
assert_eq "operation SUCCEEDED" "$(json "d['status']" <<<"$op")" "SUCCEEDED"
assert_eq "outcome FULL" "$(json "d['outcome']" <<<"$op")" "FULL"

versions=$(body_of "$(http GET "$SYNFLUX_URL/v1/sources/$SOURCE_MAIN/versions" "$TOK_A")")
assert_eq "exactly one version" "$(json "len(d)" <<<"$versions")" "1"
VERSION_ID=$(json "d[0]['version_id']" <<<"$versions")
VERSION_REF=$(json "d[0]['content_cache_reference']" <<<"$versions")
VERSION_DIGEST=$(json "d[0]['content_digest']" <<<"$versions")
assert_not_empty "version_id present" "$VERSION_ID"
assert_not_empty "content_cache_reference present — two-path resolution" "$VERSION_REF"
assert_eq "version digest matches fixture bytes" "$VERSION_DIGEST" "sha256:$FIXTURE_SHA"

step "Criterion 2 — content artifact retrievable via provenance_ref"
out=$(http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$TOK_A" '{}')
[ "$(status_of "$out")" = "200" ] || die "projection rebuild failed: $(body_of "$out")"
pass "projection rebuilt"

out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"$TOKEN_MARK\"}")
[ "$(status_of "$out")" = "200" ] || die "search failed: $(body_of "$out")"
HIT_COUNT=$(json "len(d['results'])" <<<"$(body_of "$out")")
[ "$HIT_COUNT" -ge 1 ] || die "expected at least one result"
OBJECT_ID=$(json "d['results'][0]['object_id']" <<<"$(body_of "$out")")
PROVENANCE_REF=$(json "d['results'][0]['provenance_ref']" <<<"$(body_of "$out")")
pass "search hit object_id=$OBJECT_ID"

prov=$(http GET "$SYNQUEST_URL$PROVENANCE_REF" "$TOK_A")
assert_eq "provenance_ref resolves — live URL, not constructed" "$(status_of "$prov")" "200"
assert_eq "provenance source_version_ids contains the version" \
    "$(json "d['source_version_ids'][0]" <<<"$(body_of "$prov")")" "$VERSION_ID"
assert_eq "provenance content_cache_reference matches the version" \
    "$(json "d['content_cache_reference']" <<<"$(body_of "$prov")")" "$VERSION_REF"
assert_eq "provenance digest matches fixture bytes" \
    "$(json "d['source_sha256']" <<<"$(body_of "$prov")")" "$FIXTURE_SHA"

step "Criterion 3 — restart recovery (SYNANTON_FAULT_INJECT=after-manifest)"
out=$(http POST "$SYNFLUX_URL/v1/sources" "$TOK_A" "{\"source_id\":\"$SOURCE_RECOVERY\",\"connector_id\":\"slice-conn\",\"connector_type\":\"file\",\"source_endpoint\":\"file://$FIXTURE_ROOT/$SOURCE_RECOVERY\",\"capability_flags\":[]}")
[ "$(status_of "$out")" = "201" ] || die "recovery source creation failed: $(body_of "$out")"

stop_worker
start_worker after-manifest
wait_health synflux "$SYNFLUX_URL"

out=$(http POST "$SYNFLUX_URL/v1/ingestion-runs" "$TOK_A" \
    "{\"source_id\":\"$SOURCE_RECOVERY\",\"resource_id\":\"recovery-doc.md\"}")
[ "$(status_of "$out")" = "202" ] || die "recovery run submission failed: $(body_of "$out")"
RECOVERY_OP=$(json "d['operation_id']" <<<"$(body_of "$out")")

# Worker crashes at the after-manifest point (version + manifest + chunks
# committed, run not COMPLETED — the #293 visibility-gate window).
for _ in $(seq 1 60); do
    worker_crashed && break
    sleep 2
done
worker_crashed || die "worker did not crash on the injected fault (after-manifest)"
pass "worker crashed at the injected point (exit 70)"
# Drop the fault var: the clean recreate lets recovery resume (one-shot per
# process — with the var still baked in, the pod would crash-loop).
stop_worker
start_worker
wait_health synflux "$SYNFLUX_URL"

op=$(wait_operation "$RECOVERY_OP")
assert_eq "recovered operation SUCCEEDED" "$(json "d['status']" <<<"$op")" "SUCCEEDED"
assert_eq "recovered outcome FULL" "$(json "d['outcome']" <<<"$op")" "FULL"
rversions=$(body_of "$(http GET "$SYNFLUX_URL/v1/sources/$SOURCE_RECOVERY/versions" "$TOK_A")")
assert_eq "exactly one SourceVersion after recovery — no duplicates" \
    "$(json "len(d)" <<<"$rversions")" "1"

# The SourcePublished event rides inline on the version row
# (insertSourceVersionAndOutboxIfAbsent — IF NOT EXISTS): a duplicate
# recovery pass cannot fork it. event_published flips only when the Kafka
# adapter is up; if it is false here, dispatch is down, not dedup.
events=$(cqlsh_query "SELECT version_id, event_published FROM ingestion_cache.source_versions WHERE tenant_id='$TENANT_A' AND connector_id='slice-conn' AND source_id='$SOURCE_RECOVERY' AND resource_id='recovery-doc.md';")
case "$events" in
    "") printf '  SKIP exactly one SourcePublished — no cqlsh for profile %s\n' "$WORKER_PROFILE" ;;
    *) assert_eq "exactly one SourcePublished event — deduped" \
        "$(grep -c '| True' <<<"$events")" "1" ;;
esac

step "Criterion 4 — search returns the correct hit"
GEN_BEFORE=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")" 2>/dev/null || true)
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"restart clause\"}")
[ "$(status_of "$out")" = "200" ] || die "recovery search failed"
RECOVERY_ID=$(json "d['results'][0]['object_id']" <<<"$(body_of "$out")")
assert_not_empty "recovery content searchable through the public API" "$RECOVERY_ID"
assert_eq "metadata.strategy is bm25 (the slice's actual leg)" \
    "$(json "d['metadata']['strategy']" <<<"$(body_of "$out")")" "bm25"
GEN_AFTER=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")")
assert_not_empty "configuration_generation present" "$GEN_AFTER"

http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$TOK_A" '{}' > /dev/null
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"restart clause\"}")
GEN_REBUILT=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")")
[ "$GEN_REBUILT" -gt "$GEN_AFTER" ] && pass "configuration_generation increased on rebuild — $GEN_AFTER to $GEN_REBUILT" \
    || die "configuration_generation must increase on rebuild — $GEN_AFTER to $GEN_REBUILT"

rprov=$(http GET "$SYNQUEST_URL$(json "d['results'][0]['provenance_ref']" <<<"$(body_of "$out")")" "$TOK_A")
assert_eq "recovery hit's provenance_ref resolves" "$(status_of "$rprov")" "200"
RVERSION_ID=$(json "d[0]['version_id']" <<<"$rversions")
assert_eq "recovery hit references the recovered version" \
    "$(json "d['source_version_ids'][0]" <<<"$(body_of "$rprov")")" "$RVERSION_ID"

step "Criterion 5 — cross-tenant denial"
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_B" "{\"query\":\"$TOKEN_MARK\"}")
assert_eq "tenant B sees zero results" "$(json "len(d['results'])" <<<"$(body_of "$out")")" "0"

out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_B" \
    "{\"tenant\":\"$TENANT_A\",\"query\":\"$TOKEN_MARK\"}")
assert_eq "body tenant ignored (still zero results)" "$(json "len(d['results'])" <<<"$(body_of "$out")")" "0"

out=$(http GET "$SYNQUEST_URL/v1/knowledge/$OBJECT_ID/provenance" "$TOK_B")
assert_eq "provenance 404 for the wrong tenant — visibility enforced" "$(status_of "$out")" "404"

step "Vertical slice: all five criteria pass"
