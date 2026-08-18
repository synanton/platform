#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "Building standalone Syntology demo..."
./gradlew :java:syntology:clean :java:syntology:build

echo "Starting demo at http://localhost:8080"
exec ./gradlew :java:syntology:bootRun -PskipUi
