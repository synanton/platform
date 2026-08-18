---
name: 111-java-gradle-dependencies
description: Gradle dependency management practices for the Synanton platform.
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Dependency Management - Synanton

## Dependency Scopes

| Scope | Use case |
|-------|----------|
| `implementation` | Dependency used internally; not exposed to consumers |
| `api` | Dependency that is part of the module's public API (use sparingly) |
| `compileOnly` | Compile-time only (e.g. Lombok, annotations) |
| `annotationProcessor` | Annotation processors (Lombok, MapStruct) |
| `testImplementation` | Test-only dependency |
| `testRuntimeOnly` | Runtime-only for tests (e.g. H2 for in-memory DB) |
| `runtimeOnly` | Runtime-only for production (e.g. JDBC driver) |

**Rule:** Default to `implementation`. Only use `api` when a type from the dependency appears in the module's public method signatures.

## Version Catalog Usage

Always declare versions in `gradle/libs.versions.toml`:

```toml
[versions]
spring-boot = "3.3.0"

[libraries]
spring-boot-starter-data-cassandra = { module = "org.springframework.boot:spring-boot-starter-data-cassandra", version.ref = "spring-boot" }
```

In `build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.spring.boot.starter.data.cassandra)
}
```

## BOM Imports

Import BOMs via `platform()` to let the BOM manage versions:

```kotlin
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-web")  // no version needed
}
```

## Inspecting the Dependency Tree

```bash
./gradlew :java:synquest:dependencies --configuration compileClasspath
./gradlew :java:synquest:dependencyInsight --dependency cassandra-driver
```

## Resolving Conflicts

Use `resolutionStrategy` in `build.gradle.kts`:

```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            useVersion(libs.versions.netty.get())
            because("Align all Netty modules to avoid ClassCastException")
        }
    }
}
```

Or add a dependency constraint:

```kotlin
dependencies {
    constraints {
        implementation("io.netty:netty-all") {
            version { require(libs.versions.netty.get()) }
            because("gRPC and Cassandra driver must use the same Netty version")
        }
    }
}
```
