---
name: 112-java-gradle-plugins
description: Use when you need to add, configure, or troubleshoot Gradle plugins - including Spring Boot, protobuf/gRPC, checkstyle, JaCoCo, OWASP dependency-check, or custom convention plugins in buildSrc.
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Plugin Configuration

Configure Gradle plugins for the Synanton multi-module build.

**Core areas:** `pluginManagement` in `settings.gradle.kts`, `buildSrc` convention plugins, Spring Boot plugin, protobuf + gRPC + PGV plugin, checkstyle, JaCoCo, version catalog plugin aliases, and avoiding plugin version drift across modules.

**Prerequisites:** Run `./gradlew build --dry-run` before applying any changes.

## Reference

See [references/112-java-gradle-plugins.md](references/112-java-gradle-plugins.md).
