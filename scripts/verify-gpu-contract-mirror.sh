#!/usr/bin/env bash
#
# Verifies that the synanton.gpu.v1 protobuf contract is byte-identical
# between the platform repository and the gpu-runtime repository.
#
# Usage:  ./scripts/verify-gpu-contract-mirror.sh [path-to-other-repo]
#
# Exit codes:
#   0  contracts match, or the peer repository is absent (skipped)
#   1  contracts diverge
#
# Override the peer with GPU_PEER_REPO. When the peer is not present the check
# is SKIPPED rather than failed. CI that must enforce the mirror should check
# out both repositories and pass the path.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIS_REPO="$(cd "${SCRIPT_DIR}/.." && pwd)"

REL_PROTO_PATH="java/gpu-contract/src/main/proto/synanton/gpu/v1"

if [[ $# -ge 1 ]]; then
    PEER_REPO="$1"
elif [[ -n "${GPU_PEER_REPO:-}" ]]; then
    PEER_REPO="${GPU_PEER_REPO}"
else
    case "$(basename "${THIS_REPO}")" in
        platform) PEER_REPO="$(dirname "${THIS_REPO}")/gpu-runtime" ;;
        *)        PEER_REPO="$(dirname "${THIS_REPO}")/platform" ;;
    esac
fi

THIS_PROTO="${THIS_REPO}/${REL_PROTO_PATH}"
PEER_PROTO="${PEER_REPO}/${REL_PROTO_PATH}"

if [[ ! -d "${THIS_PROTO}" ]]; then
    echo "FAIL: no GPU contract directory in this repository: ${THIS_PROTO}" >&2
    exit 1
fi

if [[ ! -d "${PEER_PROTO}" ]]; then
    echo "SKIP: peer GPU contract not found at ${PEER_PROTO}"
    echo "      Set GPU_PEER_REPO, or pass the peer repo path, to enforce."
    exit 0
fi

if diff -ru "${THIS_PROTO}" "${PEER_PROTO}"; then
    file_count="$(find "${THIS_PROTO}" -name '*.proto' -type f | wc -l | tr -d ' ')"
    echo "OK: synanton.gpu.v1 contract matches (${file_count} proto file(s))"
    echo "    this: ${THIS_PROTO}"
    echo "    peer: ${PEER_PROTO}"
    exit 0
fi

cat >&2 <<'EOF'

FAIL: the synanton.gpu.v1 contract has diverged between repositories.

The two copies MUST be byte-identical, including java_package, RPC names, and
the error catalogue. Until they match, gpu-runtime cannot serve platform clients.

To fix: copy the intended version over the other, in the SAME change set, then
re-run this check. Do not "resolve" a divergence by editing only one side.
EOF
exit 1
