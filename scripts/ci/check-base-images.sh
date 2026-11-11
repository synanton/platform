#!/usr/bin/env bash
# Enforce ADR-024: runtime images for native-dependency services must use glibc.
# Keep this list in sync with any service that pulls grpc-netty-shaded,
# netty-tcnative, JNI/JNA, or a native JDBC driver.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

dockerfiles=(
    deployments/docker/synflux.Dockerfile
    deployments/docker/synflux.prebuilt.Dockerfile
    deployments/docker/synquest.Dockerfile
    deployments/docker/synquest.prebuilt.Dockerfile
    deployments/docker/gateway.Dockerfile
    deployments/docker/extraction-gateway.prebuilt.Dockerfile
    # gpu-gateway is built from the gpu-runtime repository today. Add its
    # Dockerfile here when that build moves into this repository.
)

failed=0
for file in "${dockerfiles[@]}"; do
    if [[ ! -f "$file" ]]; then
        echo "WARN: native-dependency Dockerfile not found: $file"
        continue
    fi
    if grep -Eiq '^[[:space:]]*FROM[[:space:]]+[^[:space:]]*alpine([[:space:]]|$)' "$file"; then
        echo "ERROR: $file uses an Alpine/musl base image."
        echo "       Native gRPC/Netty/JNI dependencies require glibc (ADR-024)."
        echo "       Use eclipse-temurin:21-jre-jammy or an equivalent glibc image."
        failed=1
    fi
done

if (( failed != 0 )); then
    exit 1
fi

echo "OK: native-dependency runtime images are glibc-based."
