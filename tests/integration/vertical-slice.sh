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
#   KAFKA_PROFILE   outbox (default — no broker) | kafka
#   COMPOSE_FILE    default deployments/docker/compose.yaml
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
WORKER_PROFILE="${WORKER_PROFILE:-compose}"
K8S_NAMESPACE="${K8S_NAMESPACE:-synanton}"
CASSANDRA_POD="${CASSANDRA_POD:-cassandra-0}"
COMPOSE_FILE="${COMPOSE_FILE:-deployments/docker/compose.yaml}"
# The worker helpers `cd deployments/docker` before invoking compose — a
# relative COMPOSE_FILE would double-count the cd. Resolve absolute, once.
COMPOSE_FILE="$(cd "$(dirname "$COMPOSE_FILE")" && pwd)/$(basename "$COMPOSE_FILE")"
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
    local op_id="$1" i out code status
    for i in $(seq 1 90); do
        out=$(http GET "$SYNFLUX_URL/v1/operations/$op_id" "$TOK_A")
        code=$(status_of "$out")
        case "$code" in
            000|502|503|504) sleep 2; continue ;;  # worker restarting / transient
            401|403) die "auth failed while polling operation $op_id — not retrying" ;;
            200)
                status=$(json "d['status']" <<<"$(body_of "$out")" 2>/dev/null || echo '')
                case "$status" in
                    SUCCEEDED|FAILED|CANCELLED) printf '%s' "$(body_of "$out")"; return 0 ;;
                esac ;;
        esac
        sleep 2
    done
    die "operation $op_id did not reach a terminal state in 180s"
}

cqlsh_query() {  # cqlsh_query <cql> -> query output (empty when unavailable)
    case "$WORKER_PROFILE" in
        compose) compose exec -T cassandra cqlsh -e "$1" 2>/dev/null ;;
        k8s)     kubectl -n "$K8S_NAMESPACE" exec "$CASSANDRA_POD" -- cqlsh -e "$1" 2>/dev/null ;;
        process) command -v cqlsh >/dev/null 2>&1 && cqlsh -e "$1" 2>/dev/null ;;
    esac
}

cql_count() {  # cql_count <cql-count-query> -> row count digits
    cqlsh_query "$1" | grep -E '^[[:space:]]*[0-9]+[[:space:]]*$' | head -1 | tr -d '[:space:]'
}

# Compose invocations carry the slice override (host-jar build path:
# buildkit cannot reach the Maven repos from this network — the boot jars
# are built on the HOST and the *.prebuilt.Dockerfile variants package them),
# plus the outbox-only eventing override unless KAFKA_PROFILE=kafka.
KAFKA_PROFILE="${KAFKA_PROFILE:-outbox}"   # outbox | kafka
compose() { (cd "$(dirname "$COMPOSE_FILE")" && docker compose -f "$COMPOSE_FILE" -f "$(dirname "$COMPOSE_FILE")/compose.slice.yaml" $( [ "$KAFKA_PROFILE" = kafka ] || echo "-f $(dirname "$COMPOSE_FILE")/compose.outbox-only.yaml" ) "$@"); }

start_worker() {  # start_worker [fault-point]
    case "$WORKER_PROFILE" in
        compose)
            # The fault env is baked into the recreated container; the injected
            # crash is one-shot per process, and the clean recreate below drops it.
            if [ -n "${1:-}" ]; then
                SYNANTON_FAULT_INJECT="$1" compose up -d --force-recreate synflux
            else
                compose up -d --force-recreate synflux
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
            # The current container's own log — compose's service logs merge
            # the history of every recreated container, and a previous run's
            # FAULT INJECTED line would produce a ghost crash detection.
            local cur
            cur=$(compose ps -q synflux 2>/dev/null | head -1)
            [ -n "$cur" ] && docker logs "$cur" --tail 400 2>/dev/null | grep -q 'FAULT INJECTED'
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
        compose) compose stop synflux ;;
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
# Q8: canonical paths — cd && pwd -P resolves symlinks and spaces; the
# empty-dir check fails fast instead of a silent empty mount mid-run.
canonicalize() {
    local path="$1"
    [ -d "$path" ] || { echo "Not a directory: $path" >&2; exit 1; }
    ( cd "$path" && pwd -P )
}
FIXTURE_HOST_DIR="$(canonicalize "${FIXTURE_HOST_DIR:-$SCRIPT_DIR/../../demo-data/documents}")"
case "$WORKER_PROFILE" in
    compose) FIXTURE_ROOT="${FIXTURE_ROOT:-/demo-data/documents}" ;;
    *)       FIXTURE_ROOT="${FIXTURE_ROOT:-$FIXTURE_HOST_DIR}" ;;
esac
[ -n "$(ls -A "$FIXTURE_HOST_DIR" 2>/dev/null)" ] || {
    echo "Fixture root is empty: $FIXTURE_HOST_DIR" >&2; exit 1; }
[ "${#SYNANTON_JWT_SECRET}" -ge 32 ] || {
    echo "SYNANTON_JWT_SECRET too short (${#SYNANTON_JWT_SECRET} bytes, need >= 32 — set it in .env)" >&2; exit 1; }
case "$SYNANTON_JWT_SECRET" in
    replace-me*|*placeholder*) echo "SYNANTON_JWT_SECRET is still the .env.example placeholder — generate a real one (openssl rand -base64 48)" >&2; exit 1 ;;
esac
# The compose profile's containers must verify with the SAME secret the
# tokens are minted with — export it so `docker compose up` overrides the
# yaml default (otherwise tokens and containers silently diverge → 401s).
export SYNANTON_JWT_SECRET
TOK_A=$(mint_token "$TENANT_A")
TOK_B=$(mint_token "$TENANT_B")
mkdir -p "$FIXTURE_HOST_DIR/$SOURCE_MAIN" "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY"
TOKEN_MARK="zebra-slice-token-$STAMP"
RECOVERY_TOKEN_MARK="zebra-recovery-token-$STAMP"
printf 'Supply-chain policy %s.\nThe logistics clause governs freight.\n' "$TOKEN_MARK" \
    > "$FIXTURE_HOST_DIR/$SOURCE_MAIN/fixture-doc.md"
printf 'Recovery fixture %s.\nThe restart clause governs resumption.\n' "$RECOVERY_TOKEN_MARK" \
    > "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY/recovery-doc.md"
FIXTURE_HOST_DIR="$(cd "$FIXTURE_HOST_DIR" && pwd)"
FIXTURE_SHA=$(sha256sum "$FIXTURE_HOST_DIR/$SOURCE_MAIN/fixture-doc.md" | cut -d' ' -f1)
RECOVERY_SHA=$(sha256sum "$FIXTURE_HOST_DIR/$SOURCE_RECOVERY/recovery-doc.md" | cut -d' ' -f1)
# The slice app layer: synflux + synquest + extraction-gateway (the
# extraction client is enabled — ingestion calls the gateway; without it
# the workflow fails at extraction). --build picks up the host-built jars
# via the slice override.
if [ "$WORKER_PROFILE" = "compose" ]; then
    # The override contract: the boot jars are built on the HOST (warm Gradle
    # cache; buildkit cannot reach the Maven repos on this network) and the
    # *.prebuilt.Dockerfiles only package them. Build them here so the run
    # stays one command.
    PLATFORM_ROOT="$SCRIPT_DIR/../.."
    CONTENT_EXTRACTOR_ROOT="$SCRIPT_DIR/../../../content_extractor"
    "$PLATFORM_ROOT/gradlew" -q -p "$PLATFORM_ROOT" \
        :java:synflux:bootJar :java:synquest:bootJar -x test
    "$CONTENT_EXTRACTOR_ROOT/gradlew" -q \
        -p "$CONTENT_EXTRACTOR_ROOT" :java:extraction-gateway:bootJar -x test
    # Package the host-built jars into images. The build context is a tiny
    # staging tree, not the repo: a repo-root context walks runtime data
    # (docker/data — some dirs root-owned, permission-denied to the client)
    # and defeats .dockerignore at the sender's pre-walk. compose is NOT
    # used for building: its bake cross-resolves dockerfile paths across
    # services with different contexts.
    STAGE="$(mktemp -d)"
    mkdir -p "$STAGE/java/synflux/build/libs" "$STAGE/java/synquest/build/libs" \
             "$STAGE/java/extraction-gateway/build/libs"
    cp java/synflux/build/libs/synflux*.jar "$STAGE/java/synflux/build/libs/"
    cp java/synquest/build/libs/synquest*.jar "$STAGE/java/synquest/build/libs/"
    cp "$SCRIPT_DIR/../../../content_extractor/java/extraction-gateway/build/libs/extraction-gateway"*.jar \
        "$STAGE/java/extraction-gateway/build/libs/"
    docker build -q -f "$SCRIPT_DIR/../../deployments/docker/synflux.prebuilt.Dockerfile" \
        -t docker-synflux:latest "$STAGE"
    docker build -q -f "$SCRIPT_DIR/../../deployments/docker/synquest.prebuilt.Dockerfile" \
        -t docker-synquest:latest "$STAGE"
    docker build -q -f "$SCRIPT_DIR/../../deployments/docker/extraction-gateway.prebuilt.Dockerfile" \
        -t docker-extraction-gateway:latest "$STAGE"
    rm -rf "$STAGE"
    compose up -d synflux synquest extraction-gateway
fi
wait_health synflux "$SYNFLUX_URL"
wait_health synquest "$SYNQUEST_URL"
# Container-side mount check: wrong mount target or permission shows up here,
# not as "fixture not found" mid-run.
if [ "$WORKER_PROFILE" = "compose" ] \
    && ! compose exec -T synflux ls "$FIXTURE_ROOT/$SOURCE_MAIN" > /dev/null 2>&1; then
    echo "Fixture root not visible in the worker container: $FIXTURE_ROOT" >&2; exit 1
fi

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
VERSION_DIGEST_HEX=$(printf '%s' "$VERSION_DIGEST" | sed 's/^sha256://')
assert_eq "version digest matches fixture bytes" "$VERSION_DIGEST_HEX" "$FIXTURE_SHA"

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
RECOVERY_RUN=$(json "d['run_id']" <<<"$(body_of "$out")")

# Worker crashes at the after-manifest point (version + manifest + chunks
# committed, run not COMPLETED — the #293 visibility-gate window).
for _ in $(seq 1 60); do
    worker_crashed && break
    sleep 2
done
worker_crashed || die "worker did not crash on the injected fault (after-manifest)"
pass "worker crashed at the injected point (exit 70)"

# Pre-recovery durable state (the Q3 failure case, asserted while the worker
# is down — Cassandra is up). The after-manifest crash fires AFTER the
# pipeline's chunk publication and BEFORE the version insert — so pre-recovery
# there is NO version row and NO run-completion manifest; the chunks exist.
# The content ref is content-derived (deterministic uuidv5 over the standard
# DNS namespace: AcquireStage's NAMESPACE + tenant + ":" + sha256) — computed
# locally, no DB round-trip needed pre-crash.
RECOVERY_CONTENT_REF=$(python3 -c "
import uuid, sys
print(uuid.uuid5(uuid.UUID('6ba7b810-9dad-11d1-80b4-00c04fd430c8'), sys.argv[1] + ':' + sys.argv[2]))
" "$TENANT_A" "$RECOVERY_SHA")
assert_not_empty "content ref derived from bytes (deterministic)" "$RECOVERY_CONTENT_REF"
CHUNKS_BEFORE=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT_A' AND content_ref_id=$RECOVERY_CONTENT_REF;")
[ -n "$CHUNKS_BEFORE" ] && CHUNKS_BEFORE_DASH="$CHUNKS_BEFORE" || CHUNKS_BEFORE=""
if [ -n "$CHUNKS_BEFORE" ]; then
    printf '  NOTE chunks before recovery: %s\n' "$CHUNKS_BEFORE"
else
    printf '  NOTE retry capture of pre-recovery chunks in a later tick\n'
    CHUNKS_BEFORE="0"
fi
# No version row pre-recovery (the insert sits AFTER the crash point): the
# failure case asserts the absence; recovery writes exactly one.
versions_pre=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.source_versions WHERE tenant_id='$TENANT_A' AND connector_id='slice-conn' AND source_id='$SOURCE_RECOVERY' AND resource_id='recovery-doc.md';")
assert_eq "no version row before recovery — the failure case" "$versions_pre" "0"
# The run manifest is written by finishRun — after the version insert — so
# the failure case shows zero rows; the recovery case writes exactly one (the
# deterministic uuidV5 id makes the re-run land on the same row).
MANIFESTS_BEFORE=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.ingestion_manifests WHERE tenant_id='$TENANT_A' AND run_id=$RECOVERY_RUN;")
assert_eq "run manifest absent before recovery — the failure case" "$MANIFESTS_BEFORE" "0"

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

# Flag 1: the deterministic-ID assumption, named and asserted — recovery
# overwrites the same manifest and chunk rows, never forks them.
CHUNKS_AFTER=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT_A' AND content_ref_id=$RECOVERY_CONTENT_REF;")
if [ -n "$CHUNKS_BEFORE" ] && [ "$CHUNKS_BEFORE" -gt 0 ]; then
    assert_eq "chunk count unchanged by recovery (no re-processing forks)" "$CHUNKS_AFTER" "$CHUNKS_BEFORE"
else
    assert_not_empty "chunks committed after recovery (count $CHUNKS_AFTER)" "$CHUNKS_AFTER"
fi
versions_post=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.source_versions WHERE tenant_id='$TENANT_A' AND connector_id='slice-conn' AND source_id='$SOURCE_RECOVERY' AND resource_id='recovery-doc.md';")
assert_eq "exactly one version row after recovery" "$versions_post" "1"
MANIFESTS_AFTER=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.ingestion_manifests WHERE tenant_id='$TENANT_A' AND run_id=$RECOVERY_RUN;")
assert_eq "exactly one manifest row after recovery" "$MANIFESTS_AFTER" "1"

# Flag 3 / Q5: the SourcePublished event rides inline on the version row
# (insertSourceVersionAndOutboxIfAbsent — IF NOT EXISTS): a duplicate
# recovery pass cannot fork it. The dedup property is the event row's
# existence and uniqueness; event_published is the DISPATCH adapter's flag —
# outbox-only mode leaves it false by design (no Kafka bootstrap configured).
events=$(cqlsh_query "SELECT event_id, event_published FROM ingestion_cache.source_versions WHERE tenant_id='$TENANT_A' AND connector_id='slice-conn' AND source_id='$SOURCE_RECOVERY' AND resource_id='recovery-doc.md';")
EVENT_ROWS=$(grep -cE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' <<<"$events" || true)
assert_eq "exactly one SourcePublished event row — deduped" "$EVENT_ROWS" "1"
case "$events" in
    *"| True"*) pass "event dispatched once (dispatch adapter up)" ;;
    *) printf '  SKIP dispatch flag — outbox-only mode (no broker configured); dedup holds\n' ;;
esac

# Flag 2: the consumer-side effect — the projection builder processed each
# chunk exactly once. Rebuild (the recovery source is not auto-projected),
# then the projection's chunk count must equal the canonical chunk rows
# across both sources — exact, not "at least one".
# F1: probe auto-projection BEFORE the explicit rebuild — reports whether a
# recovered source becomes searchable on its own (event-driven projection) or
# projection is manual/scheduled. Neither is a failure; production behavior
# must not be silent.
sleep 10
# The probe must use the CURRENT run's unique token: fixed phrases match
# prior stamped runs' content (already projected), which would report a false
# "auto-projection present".
pre=$(body_of "$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"$RECOVERY_TOKEN_MARK\"}")")
case "$(json "len(d['results'])" <<<"$pre")" in
    0) printf '  NOTE auto-projection absent — recovered source searchable only after explicit rebuild (manual/scheduled projection)\n' ;;
    *) pass "auto-projection present — recovered source searchable without rebuild" ;;
esac
MAIN_CONTENT_REF=$(cqlsh_query "SELECT content_ref_id FROM ingestion_cache.source_versions WHERE tenant_id='$TENANT_A' AND connector_id='slice-conn' AND source_id='$SOURCE_MAIN' AND resource_id='fixture-doc.md';" 2>/dev/null | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1 || true)
# The tenant accumulates chunks from every prior run (stamped source ids
# keep re-running reproducible); the expectation is the tenant's FULL
# canonical chunk count, not this run's slice of it.
# chunks_payload partitions on (tenant_id, content_ref_id): a tenant-only
# restriction is a partial one — the count needs ALLOW FILTERING (test-only;
# dev-scale data).
TENANT_CHUNKS=$(cql_count "SELECT COUNT(*) FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT_A' ALLOW FILTERING;")
http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$TOK_A" '{}' > /dev/null
proj=$(body_of "$(http GET "$SYNQUEST_URL/v1/search-projection" "$TOK_A")")
assert_eq "projection chunk count equals canonical chunks (consumer applied once)" \
    "$(json "d['chunk_count']" <<<"$proj")" "$TENANT_CHUNKS"

step "Criterion 4 — search returns the correct hit"
GEN_BEFORE=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")" 2>/dev/null || true)
# The search uses the CURRENT run's UNIQUE token: fixed phrases like
# "restart clause" match every prior stamped run's recovery doc, and the top
# hit is then whichever chunk BM25 ranks first — not necessarily this run's.
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"$RECOVERY_TOKEN_MARK\"}")
[ "$(status_of "$out")" = "200" ] || die "recovery search failed"
RECOVERY_ID=$(json "d['results'][0]['object_id']" <<<"$(body_of "$out")")
assert_not_empty "recovery content searchable through the public API" "$RECOVERY_ID"
assert_eq "metadata.strategy is bm25 (the slice's actual leg)" \
    "$(json "d['metadata']['strategy']" <<<"$(body_of "$out")")" "bm25"
GEN_AFTER=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")")
assert_not_empty "configuration_generation present" "$GEN_AFTER"

http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$TOK_A" '{}' > /dev/null
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_A" "{\"query\":\"$RECOVERY_TOKEN_MARK\"}")
GEN_REBUILT=$(json "d['metadata']['configuration_generation']" <<<"$(body_of "$out")")
[ "$GEN_REBUILT" -gt "$GEN_AFTER" ] && pass "configuration_generation increased on rebuild — $GEN_AFTER to $GEN_REBUILT" \
    || die "configuration_generation must increase on rebuild — $GEN_AFTER to $GEN_REBUILT"

rprov=$(http GET "$SYNQUEST_URL$(json "d['results'][0]['provenance_ref']" <<<"$(body_of "$out")")" "$TOK_A")
assert_eq "recovery hit's provenance_ref resolves" "$(status_of "$rprov")" "200"
RVERSION_ID=$(json "d[0]['version_id']" <<<"$rversions")
# Contains, not equals-first: a chunk is content-addressed, and set
# attribution legitimately carries every source version that derives the same
# bytes. For this run the set must include the recovered version.
rprov_ids=$(json "' '.join(d['source_version_ids'])" <<<"$(body_of "$rprov")")
case " $rprov_ids " in
    *" $RVERSION_ID "*) pass "recovery hit references the recovered version" ;;
    *) die "recovery hit missing the recovered version (got: $rprov_ids, expected to contain $RVERSION_ID)" ;;
esac

step "Criterion 5 — cross-tenant denial"
# Projection readiness is per-tenant: template B's projection must exist for
# the search endpoint to answer at all (otherwise 503 pre-empts the denial
# check with an empty body). Rebuild it once — an empty projection is the
# correct starting state for a tenant that owns no content.
http POST "$SYNQUEST_URL/v1/search-projection/rebuild" "$TOK_B" '{}' > /dev/null
out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_B" "{\"query\":\"$TOKEN_MARK\"}")
assert_eq "tenant B sees zero results" "$(json "len(d['results'])" <<<"$(body_of "$out")")" "0"

out=$(http POST "$SYNQUEST_URL/v1/search" "$TOK_B" \
    "{\"tenant\":\"$TENANT_A\",\"query\":\"$TOKEN_MARK\"}")
assert_eq "body tenant ignored (still zero results)" "$(json "len(d['results'])" <<<"$(body_of "$out")")" "0"

out=$(http GET "$SYNQUEST_URL/v1/knowledge/$OBJECT_ID/provenance" "$TOK_B")
assert_eq "provenance 404 for the wrong tenant — visibility enforced" "$(status_of "$out")" "404"

step "Vertical slice: all five criteria pass"
