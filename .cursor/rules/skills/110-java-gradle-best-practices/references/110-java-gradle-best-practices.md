---
name: 110-java-gradle-best-practices
description: Detailed Gradle best practices for the Synanton multi-module Kotlin-DSL build.
license: Apache-2.0
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Best Practices - Synanton Platform

## Role

You are a senior build engineer with extensive Gradle experience on multi-module Spring Boot / Java 21 projects using Kotlin DSL.

## Goal

Effective Gradle usage in Synanton: version catalog for all dependency versions, BOM platform imports for dependency alignment, shared configuration via convention plugins, deterministic builds with locked dependency versions, and minimal boilerplate across modules.

---

## 1. Version Catalog (`gradle/libs.versions.toml`)

Centralise all versions and coordinates:

```toml
[versions]
spring-boot = "3.3.0"
grpc = "1.65.0"
protobuf = "4.27.0"
cassandra-driver = "4.18.0"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web", version.ref = "spring-boot" }
grpc-stub = { module = "io.grpc:grpc-stub", version.ref = "grpc" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

Access in build files: `libs.spring.boot.starter.web`, `libs.versions.grpc.get()`.

**Rule:** No hardcoded version strings in `build.gradle.kts` files - all versions come from the catalog or from `platform()` BOM imports.

---

## 2. BOM Platform Imports

Use `platform()` to import Spring Boot BOM and other alignment BOMs:

```kotlin
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.grpc.bom))
    // Dependencies from the BOM need no version:
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

---

## 3. Convention Plugins (`buildSrc/`)

Share configuration across modules with convention plugins:

```
buildSrc/
  src/main/kotlin/
    synanton.java-conventions.gradle.kts   # Java 21, checkstyle, lombok
    synanton.spring-conventions.gradle.kts # Spring Boot, actuator
    synanton.grpc-conventions.gradle.kts   # protobuf + grpc + pgv
```

**Example `synanton.java-conventions.gradle.kts`:**

```kotlin
plugins {
    java
    checkstyle
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

checkstyle {
    configFile = rootProject.file("checkstyle.xml")
    maxWarnings = 0
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}
```

Modules apply only the conventions they need:

```kotlin
// java/synquest/build.gradle.kts
plugins {
    id("synanton.spring-conventions")
    id("synanton.grpc-conventions")
}
```

---

## 4. Multi-module Settings

`settings.gradle.kts` declares all modules:

```kotlin
rootProject.name = "synanton"

include(
    ":java:shared-common",
    ":java:ingestion-cache",
    ":java:synvault",
    ":java:synflux",
    ":java:synquest",
    ":java:relix",
    ":java:planner",
    ":java:gateway",
    ":java:synapt",
    ":java:security",
    ":java:topology",
    ":java:syntology",
    ":java:synanton-llm-client",
    ":java:control-plane",
    ":tools:synanton-ops",
)
```

---

## 5. Task Naming and Running

```bash
./gradlew compileJava                      # compile all modules
./gradlew :java:synquest:compileJava       # compile one module
./gradlew test                             # run all tests
./gradlew :java:synapt:test                # run one module's tests
./gradlew :java:gateway:bootRun            # start a service
./gradlew dependencies --configuration compileClasspath  # inspect deps
./gradlew build                            # full build
```

---

## 6. Common Anti-Patterns to Avoid

| Anti-pattern | Fix |
|---|---|
| Hardcoded version string in `build.gradle.kts` | Move to version catalog |
| Copy-pasted plugin config in every module | Extract to convention plugin in `buildSrc/` |
| Using `compile` scope (Gradle 7+ removed it) | Use `implementation` or `api` |
| `api` dependency when `implementation` suffices | Use `implementation` to avoid leaking transitive deps |
| Missing `testImplementation` for test-only deps | Separate compile and test scopes correctly |
| `allprojects { dependencies { ... } }` | Use convention plugins instead |
