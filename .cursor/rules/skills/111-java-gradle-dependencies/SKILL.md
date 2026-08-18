---
name: 111-java-gradle-dependencies
description: Use when you need to add, update, or audit dependencies in a Gradle Kotlin-DSL build - including resolving version conflicts, using the version catalog, importing BOMs, checking for CVEs, or understanding the dependency tree.
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Dependency Management

Manage dependencies in a multi-module Gradle build using version catalogs and BOM platform imports.

**Core areas:** `gradle/libs.versions.toml` version catalog, `platform()` BOM imports, `implementation` vs `api` scoping, dependency constraints, version conflict resolution, `./gradlew dependencies` output analysis, and OWASP dependency-check integration.

**Prerequisites:** Run `./gradlew build --dry-run` before applying any changes. If it fails, stop.

**Before applying changes:** Read the reference for detailed examples and constraints.

## Reference

See [references/111-java-gradle-dependencies.md](references/111-java-gradle-dependencies.md).
