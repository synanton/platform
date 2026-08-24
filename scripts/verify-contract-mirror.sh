#!/usr/bin/env bash
#
# Verifies that the synanton.extraction.v1 protobuf contract is byte-identical
# between the platform repository and the content_extractor repository.
#
# Why this exists: the synanton.gpu.v1 "mirror" silently diverged. The platform
# holds one file under org.synanton.gpu.v1 with a GetStatus RPC; gpu-runtime
# holds four files under com.synanton.gpu.v1 with a StatusRequest RPC. Nothing
# failed, because nothing checked. This script is that check for v1.21.
#
# Usage:  ./scripts/verify-contract-mirror.sh [path-to-other-repo]
#
# Exit codes:
#   0  contracts match, or the peer repository is absent (skipped)
#   1  contracts diverge
#
# The peer location may be overridden with EXTRACTION_PEER_REPO. When the peer
# is not present (a CI job that checks out only one repository), the check is
# SKIPPED rather than failed: a missing peer is not evidence of divergence.
# CI that must enforce the mirror should check out both and set the path.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIS_REPO="$(cd "${SCRIPT_DIR}/.." && pwd)"

REL_PROTO_PATH="java/extraction-contract/src/main/proto/synanton/extraction/v1"

# Resolve the peer repository: explicit argument, then env var, then sibling.
if [[ $# -ge 1 ]]; then
    PEER_REPO="$1"
elif [[ -n "${EXTRACTION_PEER_REPO:-}" ]]; then
    PEER_REPO="${EXTRACTION_PEER_REPO}"
else
    case "$(basename "${THIS_REPO}")" in
        platform) PEER_REPO="$(dirname "${THIS_REPO}")/content_extractor" ;;
        *)        PEER_REPO="$(dirname "${THIS_REPO}")/platform" ;;
    esac
fi

THIS_PROTO="${THIS_REPO}/${REL_PROTO_PATH}"
PEER_PROTO="${PEER_REPO}/${REL_PROTO_PATH}"

if [[ ! -d "${THIS_PROTO}" ]]; then
    echo "FAIL: no contract directory in this repository: ${THIS_PROTO}" >&2
    exit 1
fi

if [[ ! -d "${PEER_PROTO}" ]]; then
    echo "SKIP: peer contract not found at ${PEER_PROTO}"
    echo "      Set EXTRACTION_PEER_REPO, or pass the peer repo path, to enforce."
    exit 0
fi

if diff -ru "${THIS_PROTO}" "${PEER_PROTO}"; then
    file_count="$(find "${THIS_PROTO}" -name '*.proto' -type f | wc -l | tr -d ' ')"
    echo "OK: synanton.extraction.v1 contract matches (${file_count} proto file(s))"
    echo "    this: ${THIS_PROTO}"
    echo "    peer: ${PEER_PROTO}"
    exit 0
fi

cat >&2 <<'EOF'

FAIL: the synanton.extraction.v1 contract has diverged between repositories.

The two copies MUST be byte-identical, including the java_package option: the
contract is what makes extraction topology-independent, and a contract that
differs per repository is not one contract.

To fix: copy the intended version over the other, in the SAME change set, then
re-run this check. Do not "resolve" a divergence by editing only one side.
EOF
exit 1
