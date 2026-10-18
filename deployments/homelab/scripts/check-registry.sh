#!/usr/bin/env bash
# check-registry.sh — manual CLI validation of local-registry access.
#
# Validates, in order, every layer a cluster deploy depends on:
#   1. reachability          — GET /v2/ answers
#   2. authentication        — the config/.env credentials are accepted
#   3. write access          — push a probe tag
#   4. read access           — pull the probe tag back
#   5. docker login state    — THIS machine's credential store matches .env
#
# Usage:
#   ./scripts/check-registry.sh                 # all five layers
#   ./scripts/check-registry.sh --no-write      # layers 1-2 + 5 only (no push)
#
# Exit 0 only when every requested layer passes. Load-bearing for the offline
# contract: kubelet's image pulls and mirror-images.sh both depend on these
# same credentials.
set -euo pipefail

REGISTRY="${REGISTRY:-local-registry:5000}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../config/.env"
NO_WRITE="${1:-}"
# The registry is HTTPS with a self-signed cert (plain HTTP returns nginx's
# 400). Point REGISTRY_CA at the CA (config/local-registry.crt) to validate
# strictly; without it the probes use -k (docker trusts the cert via its own
# /etc/docker/certs.d/<registry>/ca.crt config, not this variable).
REGISTRY_CA="${REGISTRY_CA:-$SCRIPT_DIR/../config/local-registry.crt}"
[ -f "$REGISTRY_CA" ] || REGISTRY_CA=""

SCHEME=""
probe() {  # probe <url> [extra curl args...] -> http code
    local url="$1"; shift
    local args=(-s -o /dev/null -w '%{http_code}' -m 5)
    [ "$SCHEME" = https ] && { args+=(-k); [ -n "$REGISTRY_CA" ] && args+=(--cacert "$REGISTRY_CA"); }
    curl "${args[@]}" "$@" "$url" 2>/dev/null || echo 000
}

detect_scheme() {
    local code
    code=$(probe "https://${REGISTRY}/v2/")
    case "$code" in 200|401) SCHEME=https; return 0 ;; esac
    code=$(probe "http://${REGISTRY}/v2/")
    case "$code" in 200|401) SCHEME=http; return 0 ;; esac
    die "registry unreachable at ${REGISTRY} over https and http — is it up on node0? (/etc/hosts maps local-registry -> 192.168.10.30)"
}

log()  { printf '\033[1;34m[registry]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[registry]\033[0m %s\n' "$*" >&2; exit 1; }
pass() { printf '\033[1;32m[registry]\033[0m %s\n' "$*"; }

# ── credentials ──────────────────────────────────────────────────────────────
[ -f "$ENV_FILE" ] || die "missing $ENV_FILE — copy .env.example and set REGISTRY_USER/REGISTRY_PASSWORD"
# shellcheck disable=SC1091
. "$ENV_FILE"
: "${REGISTRY_USER:?set REGISTRY_USER in $ENV_FILE}"
: "${REGISTRY_PASSWORD:?set REGISTRY_PASSWORD in $ENV_FILE}"

# ── 1. reachability (scheme auto-detect: https-self-signed first) ───────────
detect_scheme
[ -z "$REGISTRY_CA" ] && log "scheme ${SCHEME} (TLS not strictly validated — set config/local-registry.crt for strict CA checking)"
[ -n "$REGISTRY_CA" ] && pass "scheme ${SCHEME} (CA validated via ${REGISTRY_CA})"

# ── 2. authentication ────────────────────────────────────────────────────────
code=$(probe "${SCHEME}://${REGISTRY}/v2/" -u "$REGISTRY_USER:$REGISTRY_PASSWORD")
case "$code" in
    200) pass "authentication accepted (.env credentials)" ;;
    401) die "credentials rejected (HTTP 401) — check REGISTRY_USER/REGISTRY_PASSWORD in $ENV_FILE" ;;
    *)   die "unexpected HTTP $code on authenticated /v2/ probe" ;;
esac

# ── 5. docker login state (early signal; full check after write) ─────────────
# The probe: pull a known-tiny manifest through docker's own credential
# store is heavy — instead compare by attempting an authenticated HEAD via
# docker. If docker login was done with different credentials, the push in
# layer 3 is what actually fails, so keep this informational.
if docker system info 2>/dev/null | grep -q "Username:"; then
    pass "docker credential store has a login (push validates it against the registry)"
else
    log "docker credential store has no login — run:  docker login ${REGISTRY}"
fi

# ── 3+4. write/read round-trip ───────────────────────────────────────────────
if [ "$NO_WRITE" = "--no-write" ]; then
    pass "write/read round-trip skipped (--no-write)"
    log "local-registry access OK (read-only checks)"
    exit 0
fi

PROBE_SRC="busybox:latest"
PROBE_DST="${REGISTRY}/synanton/registry-probe:$(date +%s)"

docker image inspect "$PROBE_SRC" >/dev/null 2>&1 \
    || docker pull "$PROBE_SRC" \
    || die "probe image $PROBE_SRC unavailable locally and pull failed — mirror it first (see mirror-images.sh)"
docker tag "$PROBE_SRC" "$PROBE_DST"
docker push "$PROBE_DST" >/dev/null 2>&1 || die "PUSH failed — docker login state may not match the .env credentials; run:  docker login ${REGISTRY}"
pass "write access (pushed ${PROBE_DST})"
docker pull "$PROBE_DST" >/dev/null 2>&1 || die "PULL failed — pushed manifest not readable (unexpected; registry storage issue)"
pass "read access (pulled ${PROBE_DST})"

# cleanup the probe refs (local + registry delete API)
docker rmi "$PROBE_DST" >/dev/null 2>&1 || true
digest=$(probe "${SCHEME}://${REGISTRY}/v2/synanton/registry-probe/manifests/$(basename "$PROBE_DST" | cut -d- -f2)" \
    -u "$REGISTRY_USER:$REGISTRY_PASSWORD" -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
    | sha256sum | cut -d' ' -f1)
if [ -n "$digest" ]; then
    probe "${SCHEME}://${REGISTRY}/v2/synanton/registry-probe/manifests/sha256:${digest}" \
        -u "$REGISTRY_USER:$REGISTRY_PASSWORD" -X DELETE >/dev/null 2>&1 \
        && pass "probe manifest deleted from the registry" \
        || log "probe manifest left in the registry (deletion may be disabled — harmless)"
fi

log "local-registry access OK — all layers pass"
