#!/usr/bin/env bash
set -euo pipefail

PLATFORM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PLATFORM_ROOT"
./gradlew :java:synflux:test \
  --tests 'org.synanton.synflux.document.P1ExitConformanceTest' \
  --tests 'org.synanton.synflux.document.DocumentStructureServiceTest' \
  --tests 'org.synanton.synflux.pipeline.stage.ExtractionStageStructureTest'
