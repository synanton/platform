#!/usr/bin/env bash
# Mirror the platform slice's images into the home-lab registry
# (local-registry:5000) so node1-node3 never need direct internet egress.
# Pattern copied from gpu-runtime/deployments/homelab/scripts/mirror-images.sh.
#
# Two image classes:
#   1. BUILT   — synflux + synquest (this repo) and extraction-gateway (the
#                sibling content_extractor repo): built from source on THIS
#                machine, then pushed.
#   2. MIRRORED — third-party images pulled from upstream, retagged, pushed.
#
# The cluster runs offline: every image a pod references must exist in
# local-registry BEFORE deploy.sh runs. If an upstream pull fails (no
# internet on this machine, tag gone, rate limit), the script prints the
# exact operator CLI commands — run them on any internet-connected machine
# and push to the registry.
#
# Usage: ./scripts/mirror-images.sh [registry-host:port]
#
# Requires: `docker login local-registry:5000` on THIS host (the registry
# requires basic auth), and outbound internet for the mirrored images.
set -euo pipefail

case "${1:-}" in
    *=*) echo "error: '$1' looks like an environment assignment. Use the env prefix instead:" >&2
         echo "  PREBUILT=0 ./mirror-images.sh         # or PREBUILT=1 (the default)" >&2
         echo "  ./mirror-images.sh                    # registry defaults to local-registry:5000" >&2
         exit 1 ;;
esac
REGISTRY="${1:-local-registry:5000}"
TAG="slice-$(date +%Y%m%d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# The extraction gateway lives in the sibling content_extractor repo
# (compose builds it from there — deployments/docker/compose.yaml).
CONTENT_EXTRACTOR_ROOT="${CONTENT_EXTRACTOR_ROOT:-$PLATFORM_ROOT/../content_extractor}"

log()  { printf '\033[1;34m[mirror]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[mirror]\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '\033[1;33m[mirror]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[mirror]\033[0m %s\n' "$*"; }

# ── Preconditions ────────────────────────────────────────────────────────────
command -v docker >/dev/null || die "docker not found on this machine"
[ -f "$PLATFORM_ROOT/deployments/docker/synflux.Dockerfile" ] \
    || die "platform repo not found at $PLATFORM_ROOT"
# NOTE: the sibling repo keeps the singular `deployment/` (only this repo
# renamed to `deployments/`) — do not "fix" this to the plural.
CONTENT_EXTRACTOR_DOCKERFILE="$CONTENT_EXTRACTOR_ROOT/deployment/docker/extraction-gateway.Dockerfile"
[ -f "$CONTENT_EXTRACTOR_DOCKERFILE" ] \
    || die "content_extractor not found: expected $CONTENT_EXTRACTOR_DOCKERFILE (set CONTENT_EXTRACTOR_ROOT)"

HOMELAB_CONFIG="$SCRIPT_DIR/../config"

# ── Operator setup: .env + registry login ────────────────────────────────────
# The registry requires basic auth and the pushed manifests need the JWT
# secret — both live in config/.env (gitignored). Missing pieces call the
# operator with the exact commands; interactive sessions get prompted,
# non-interactive runs (CI) get instructions and a non-zero exit.

ensure_env_file() {
    if [ -f "$HOMELAB_CONFIG/.env" ]; then
        # shellcheck disable=SC1091
        . "$HOMELAB_CONFIG/.env"
        : "${SYNANTON_JWT_SECRET:?set SYNANTON_JWT_SECRET in $HOMELAB_CONFIG/.env}"
        [ "${#SYNANTON_JWT_SECRET}" -ge 32 ] \
            || die "SYNANTON_JWT_SECRET too short (${#SYNANTON_JWT_SECRET} bytes, need >= 32) — regenerate in $HOMELAB_CONFIG/.env"
        return 0
    fi
    note "$HOMELAB_CONFIG/.env is missing — it carries the JWT secret and the registry credentials (gitignored)."
    if [ ! -t 0 ]; then
        note "non-interactive session — operator action:"
        printf '      cp %s/.env.example %s/.env && edit (set SYNANTON_JWT_SECRET, REGISTRY_USER, REGISTRY_PASSWORD)\n' \
            "$HOMELAB_CONFIG" "$HOMELAB_CONFIG"
        FAILED_IMAGES+=("config/.env — create manually (see above)")
        return 1
    fi
    read -rp "Create $HOMELAB_CONFIG/.env now? [Y/n] " ans
    case "$ans" in n|N) die "aborted — create .env and re-run" ;; esac
    mkdir -p "$HOMELAB_CONFIG"
    {
        echo "# Platform homelab — generated $(date +%F). Gitignored; never commit."
        echo "# ── JWT signing key (HS256, >= 32 bytes) ──"
        if command -v openssl >/dev/null; then
            echo "SYNANTON_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')"
        else
            echo "SYNANTON_JWT_SECRET=replace-me-with-at-least-32-bytes-of-random"
            note "openssl not found — paste a random >= 32-byte secret into the generated .env"
        fi
        echo "# ── local-registry basic auth (registry probe + docker login) ──"
        echo "REGISTRY_USER="
        echo "REGISTRY_PASSWORD="
    } > "$HOMELAB_CONFIG/.env"
    chmod 600 "$HOMELAB_CONFIG/.env"
    # shellcheck disable=SC1091
    . "$HOMELAB_CONFIG/.env"
    if [ "${#SYNANTON_JWT_SECRET}" -lt 32 ]; then
        die "paste a real secret into $HOMELAB_CONFIG/.env (SYNANTON_JWT_SECRET) and re-run"
    fi
    pass ".env created (chmod 600)"
}

ensure_registry_login() {
    # Credentials from .env win; the probe hits the same endpoint kubelet's
    # image pull will hit. The registry is HTTPS with a self-signed cert
    # (plain HTTP returns nginx's 400) — probe HTTPS first with -k, then fall
    # back to HTTP. 200 = authed, 401/400 = needs auth, 000 = unreachable.
    local probe
    probe=$(curl -sk -o /dev/null -w '%{http_code}' -m 5 \
        ${REGISTRY_USER:+-u "$REGISTRY_USER:$REGISTRY_PASSWORD"} \
        "https://${REGISTRY}/v2/" 2>/dev/null || true)
    probe="${probe:-000}"
    if [ "$probe" = "000" ]; then
        probe=$(curl -s -o /dev/null -w '%{http_code}' -m 5 \
            ${REGISTRY_USER:+-u "$REGISTRY_USER:$REGISTRY_PASSWORD"} \
            "http://${REGISTRY}/v2/" 2>/dev/null || true)
        probe="${probe:-000}"
    fi
    case "$probe" in
        200) pass "registry reachable and authed (${REGISTRY})"; return 0 ;;
        401|400)
            if [ -n "${REGISTRY_USER:-}" ] && [ -t 0 ]; then
                note "registry rejected the .env credentials — re-entering"
                printf '%s' "$REGISTRY_PASSWORD" | docker login "$REGISTRY" \
                    -u "$REGISTRY_USER" --password-stdin \
                    || die "docker login failed — check REGISTRY_USER/REGISTRY_PASSWORD in .env"
            elif [ -t 0 ]; then
                docker login "$REGISTRY" || die "docker login failed"
            else
                note "non-interactive session — operator action:"
                printf '      docker login %s        # then re-run\n' "$REGISTRY"
                FAILED_IMAGES+=("registry auth — docker login (see above)")
                return 1
            fi ;;
        000) note "registry unreachable at ${REGISTRY} — is it up on node0? (/etc/hosts maps local-registry -> 192.168.10.30)"
             FAILED_IMAGES+=("registry unreachable")
             return 1 ;;
        *) note "registry probe returned HTTP $probe for ${REGISTRY}/v2/ — treating as auth-required"
           FAILED_IMAGES+=("registry auth (HTTP $probe)")
           return 1 ;;
    esac
}

FAILED_IMAGES=()
SETUP_OK=1
ensure_env_file || SETUP_OK=0
[ "$SETUP_OK" = 1 ] && { ensure_registry_login || SETUP_OK=0; }
if [ "$SETUP_OK" != 1 ]; then
    die "operator setup incomplete (${FAILED_IMAGES[*]}) — fix and re-run; no images were mirrored"
fi
log "preconditions ok (.env + registry auth)"

# Operator instructions for one failed image — the offline-first contract:
# the script never leaves an image silently missing from the registry.
manual_hint() {
    local src="$1" dest="$2"
    note "MANUAL STEP REQUIRED for $src — run on any internet-connected machine:"
    printf '      docker pull %s\n      docker tag %s %s\n      docker push %s\n' "$src" "$src" "$dest" "$dest"
    FAILED_IMAGES+=("$dest")
}

# ── 1. Built images (platform + content_extractor source) ───────────────────
# PREBUILT=1 (default here): build the boot jars on the HOST (warm Gradle
# cache; buildkit cannot reach the Maven repos on this network — TLS resets
# observed 2026-10-18) and package them with the *.prebuilt.Dockerfile
# variants from a tiny staging context. PREBUILT=0 uses the multi-stage
# Dockerfiles (works where buildkit has Maven egress).
PREBUILT="${PREBUILT:-1}"

prebuilt_stage() {  # prebuilt_stage <name> <host-jar-glob>
    local name="$1" jar_glob="$2" stage
    stage="$(mktemp -d)"
    mkdir -p "$stage/java/$name/build/libs"
    cp $jar_glob "$stage/java/$name/build/libs/"
    echo "$stage"
}

build_and_push() {
    local name="$1" context="$2" dockerfile="$3"
    local dest="${REGISTRY}/synanton/${name}:${TAG}"
    if [ "$PREBUILT" = "1" ]; then
        dockerfile="$PLATFORM_ROOT/deployments/docker/${name}.prebuilt.Dockerfile"
        [ -f "$dockerfile" ] || { note "no prebuilt Dockerfile for ${name}"; build_full "$name" "$context" "$dockerfile"; return $?; }
        log "packaging host-built ${name}:${TAG}"
        # The gateway jar comes from the sibling repo.
        local jar_glob
        case "$name" in
            extraction-gateway) jar_glob="$CONTENT_EXTRACTOR_ROOT/java/extraction-gateway/build/libs/extraction-gateway*.jar" ;;
            *)                  jar_glob="$PLATFORM_ROOT/java/$name/build/libs/$name*.jar" ;;
        esac
        local stage; stage="$(prebuilt_stage "$name" "$jar_glob")"
        if [ "$name" = "synquest" ]; then
            mkdir -p "$stage/demo-data/eval/retrieval-benchmark"
            cp "$PLATFORM_ROOT/demo-data/eval/retrieval-benchmark/ontology-v2.jsonl" \
               "$PLATFORM_ROOT/demo-data/eval/retrieval-benchmark/graph-v2.json" \
               "$PLATFORM_ROOT/demo-data/eval/retrieval-benchmark/dense-embeddings-v2.json" \
               "$PLATFORM_ROOT/demo-data/eval/retrieval-benchmark/queries-v2.rb-fixed-g5.jsonl" \
               "$stage/demo-data/eval/retrieval-benchmark/"
        fi
        docker build -f "$dockerfile" -t "$dest" "$stage" \
            || { note "build failed for ${name} — are the host jars built? (run the gradle bootJar first)"; FAILED_IMAGES+=("$dest"); rm -rf "$stage"; return 1; }
        rm -rf "$stage"
    else
        log "building ${name}:${TAG} from the multi-stage Dockerfile"
        docker build -f "$dockerfile" -t "$dest" "$context" \
            || { note "MANUAL STEP REQUIRED for ${name} — build failed; fix the source or build by hand:"
                 printf '      docker build -f %s -t %s %s\n      docker push %s\n' "$dockerfile" "$dest" "$context" "$dest"
                 FAILED_IMAGES+=("$dest")
                 return 1; }
    fi
    docker push "$dest" \
        || { note "push failed — is this machine logged in?  docker login ${REGISTRY}"
             manual_hint "$dest" "$dest"; return 1; }
    echo "  → ${dest}"
}

# Host jars for the prebuilt path (no-op if PREBUILT=0).
build_host_jars() {
    [ "$PREBUILT" = "1" ] || return 0
    log "building boot jars on the host"
    "$PLATFORM_ROOT/gradlew" -q -p "$PLATFORM_ROOT" \
        :java:synflux:bootJar :java:synquest:bootJar -x test
    "$CONTENT_EXTRACTOR_ROOT/gradlew" -q -p "$CONTENT_EXTRACTOR_ROOT" \
        :java:extraction-gateway:bootJar -x test
}

log "built images (tag ${TAG})"
build_and_push synflux              "$PLATFORM_ROOT"           "$PLATFORM_ROOT/deployments/docker/synflux.Dockerfile"  || true
build_and_push synquest             "$PLATFORM_ROOT"           "$PLATFORM_ROOT/deployments/docker/synquest.Dockerfile" || true
build_and_push extraction-gateway   "$CONTENT_EXTRACTOR_ROOT"  "$CONTENT_EXTRACTOR_ROOT/deployment/docker/extraction-gateway.Dockerfile" || true

# ── 2. Mirrored third-party images (pinned; record digests at freeze) ───────
declare -A IMAGES=(
  [cassandra]="docker.io/library/cassandra:4.1"
  [postgres]="docker.io/library/postgres:16-alpine"
  [alpine]="docker.io/library/alpine:3"
  [curlimages-curl]="docker.io/curlimages/curl:8.10.1"
  # MinIO withdrew its community images on 2025-10-23; Chainguard's free
  # rebuilds are the maintained drop-in.
  #
  # PIN: sha256 digests recorded 2026-10-19, amd64 manifest — correct for
  # M70q and the x86 cluster (native). On Apple Silicon this pulls the amd64
  # manifest under emulation (slow but functional); on an arm64-only cluster
  # use the multi-arch digest instead (or add a per-arch pin).
  # Update deliberately:
  #   docker pull chainguard/minio:latest
  #   docker inspect --format '{{index .RepoDigests 0}}' chainguard/minio:latest
  #   docker buildx imagetools inspect chainguard/minio:latest   # multi-arch
  [minio]="chainguard/minio@sha256:709099319add48dfb44aa596a7070d3ddd8dd04d7ae881765719aec29df9e510"
  [minio-mc]="chainguard/minio-client@sha256:3d0109d854f045c0158637d6ad0d44fd8a3aef9f0b62082096eb1854c2df8b31"
  # Kafka is the OPTIONAL eventing profile (outbox-only is the default) —
  # mirror it only when the Kafka path is planned:
  # [kafka]="docker.io/apache/kafka:3.7.0"
)

log "mirrored third-party images"
for local_name in "${!IMAGES[@]}"; do
  src="${IMAGES[$local_name]}"
  tag="${src##*:}"
  dest="${REGISTRY}/synanton/${local_name}:${tag}"
  echo "== ${src} -> ${dest}"
  # mirror-first: Docker Hub pulls hang on this network; mirror.gcr.io works.
  # docker.io is the fallback for images the mirror does not carry.
  # Offline-first: an image already present locally needs no pull (the
  # compose runs cache minio/mc/cassandra locally). Otherwise mirror-first
  # (Docker Hub pulls hang on this network), docker.io as the last resort.
  # NOTE: a mirror pull lands under the MIRROR's name — tag from whatever
  # name now holds the image, not blindly from $src.
  local_image="$src"
  if ! docker image inspect "$local_image" >/dev/null 2>&1; then
      mirror_src="mirror.gcr.io/${src#docker.io/}"
      if docker pull "$mirror_src" >/dev/null 2>&1; then
          local_image="$mirror_src"
      elif docker pull "${src}" >/dev/null 2>&1; then
          local_image="$src"
      else
          manual_hint "$src" "$dest"
          continue
      fi
  fi
  docker tag "$local_image" "${dest}"
  if ! docker push "${dest}" 2>/dev/null; then
      note "push failed — is this machine logged in?  docker login ${REGISTRY}"
      manual_hint "$src" "$dest"
  fi
done

# ── Summary ──────────────────────────────────────────────────────────────────
echo
if [ "${#FAILED_IMAGES[@]}" -gt 0 ]; then
    die "${#FAILED_IMAGES[@]} image(s) need manual operator action (commands above): ${FAILED_IMAGES[*]}"
fi
log "all images in ${REGISTRY} — record immutable digests for the freeze table:"
echo "  docker buildx imagetools inspect ${REGISTRY}/synanton/<name>:<tag> --format '{{.Manifest.Digest}}'"
echo "Set IMAGE_TAG=${TAG} when applying the k8s manifests (k8s/*.yaml image fields)."
