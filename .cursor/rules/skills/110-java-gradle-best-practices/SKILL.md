---
name: 110-java-gradle-best-practices
description: Use when you need to review, improve, or troubleshoot a Gradle build file - including dependency management with BOMs/platforms, plugin configuration, version catalogs, multi-module project structure, build conventions, or any situation where you want to align the Gradle setup with industry best practices for a Spring Boot Java project.
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Best Practices (Synanton)

Improve Gradle build configuration using industry-standard best practices for a multi-module Spring Boot project with Kotlin DSL (`build.gradle.kts`).

**Core areas:** Version catalog (`gradle/libs.versions.toml`), dependency management via `platform()` BOM imports, `buildSrc` or convention plugins for shared config, standard source sets, `pluginManagement` in `settings.gradle.kts`, version centralization, multi-module project structure with proper inheritance via convention plugins, and cross-module version consistency.

**Prerequisites:** Run `./gradlew build --dry-run` before applying recommendations. If it fails, **stop** and ask the user to fix issues - do not proceed until resolved.

**Multi-module scope:** After reading the root `build.gradle.kts`, check `settings.gradle.kts` for the `include(...)` list. If multi-module, read every child module's `build.gradle.kts` before making recommendations. Check each child for hardcoded versions that duplicate the version catalog, redundant plugin blocks, properties that should be centralized, and version drift across sibling modules.

**Before applying changes:** Read the reference for detailed examples, good/bad patterns, and constraints.

## Reference

For detailed guidance, examples, and constraints, see [references/110-java-gradle-best-practices.md](references/110-java-gradle-best-practices.md).
