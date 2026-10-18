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

REGISTRY="${1:-local-registry:5000}"
TAG="slice-$(date +%Y%m%d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# The extraction gateway lives in the sibling content_extractor repo
# (compose builds it from there — deployment/docker/compose.yaml).
CONTENT_EXTRACTOR_ROOT="${CONTENT_EXTRACTOR_ROOT:-$PLATFORM_ROOT/../content_extractor}"

log()  { printf '\033[1;34m[mirror]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[mirror]\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '\033[1;33m[mirror]\033[0m %s\n' "$*"; }

# ── Preconditions ────────────────────────────────────────────────────────────
command -v docker >/dev/null || die "docker not found on this machine"
[ -f "$PLATFORM_ROOT/deployment/docker/synflux.Dockerfile" ] \
    || die "platform repo not found at $PLATFORM_ROOT"
[ -f "$CONTENT_EXTRACTOR_ROOT/deployment/docker/extraction-gateway.Dockerfile" ] \
    || die "content_extractor repo not found at $CONTENT_EXTRACTOR_ROOT (set CONTENT_EXTRACTOR_ROOT)"

# Operator instructions for one failed image — the offline-first contract:
# the script never leaves an image silently missing from the registry.
manual_hint() {
    local src="$1" dest="$2"
    note "MANUAL STEP REQUIRED for $src — run on any internet-connected machine:"
    printf '      docker pull %s\n      docker tag %s %s\n      docker push %s\n' "$src" "$src" "$dest" "$dest"
    FAILED_IMAGES+=("$dest")
}

FAILED_IMAGES=()

# ── 1. Built images (platform + content_extractor source) ───────────────────
build_and_push() {
    local name="$1" context="$2" dockerfile="$3"
    local dest="${REGISTRY}/synanton/${name}:${TAG}"
    log "building ${name}:${TAG} from $(basename "$(dirname "$dockerfile")")"
    docker build -f "$dockerfile" -t "$dest" "$context" \
        || { note "MANUAL STEP REQUIRED for ${name} — build failed; fix the source or build by hand:"
             printf '      docker build -f %s -t %s %s\n      docker push %s\n' "$dockerfile" "$dest" "$context" "$dest"
             FAILED_IMAGES+=("$dest")
             return 1; }
    docker push "$dest" \
        || { note "push failed — is this machine logged in?  docker login ${REGISTRY}"
             manual_hint "$dest" "$dest"; return 1; }
    echo "  → ${dest}"
}

log "built images (tag ${TAG})"
build_and_push synflux              "$PLATFORM_ROOT"           "$PLATFORM_ROOT/deployment/docker/synflux.Dockerfile"  || true
build_and_push synquest             "$PLATFORM_ROOT"           "$PLATFORM_ROOT/deployment/docker/synquest.Dockerfile" || true
build_and_push extraction-gateway   "$CONTENT_EXTRACTOR_ROOT"  "$CONTENT_EXTRACTOR_ROOT/deployment/docker/extraction-gateway.Dockerfile" || true

# ── 2. Mirrored third-party images (pinned; record digests at freeze) ───────
declare -A IMAGES=(
  [cassandra]="docker.io/library/cassandra:4.1"
  [postgres]="docker.io/library/postgres:16-alpine"
  [alpine]="docker.io/library/alpine:3"
  [curlimages-curl]="docker.io/curlimages/curl:8.10.1"
  [minio]="docker.io/minio/minio:latest"
  [minio-mc]="docker.io/minio/mc:latest"
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
  if ! docker pull "${src}" 2>/dev/null; then
      manual_hint "$src" "$dest"
      continue
  fi
  docker tag "${src}" "${dest}"
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
