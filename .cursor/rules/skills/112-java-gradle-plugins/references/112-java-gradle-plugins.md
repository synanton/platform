---
name: 112-java-gradle-plugins
description: Gradle plugin configuration patterns for the Synanton platform.
metadata:
  author: Synanton platform team
  version: 1.0.0
---
# Gradle Plugin Configuration - Synanton

## Plugin Management in settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.springframework.boot") version libs.versions.springBoot.get()
        id("com.google.protobuf") version libs.versions.protobufPlugin.get()
    }
}
```

## Spring Boot Plugin

```kotlin
// In a Spring Boot service module:
plugins {
    id("synanton.spring-conventions")  // convention plugin from buildSrc
}
// Convention plugin applies: org.springframework.boot + io.spring.dependency-management
```

**Do not** apply `org.springframework.boot` in shared library modules - only in runnable service modules.

## Protobuf + gRPC + PGV Plugin

```kotlin
plugins {
    id("com.google.protobuf")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
        id("validate") { artifact = "build.buf:protoc-gen-validate:${libs.versions.pgv.get()}" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("validate") { option("lang=java") }
            }
        }
    }
}

// Regenerate after .proto changes:
// ./gradlew :java:<module>:generateProto
```

## Checkstyle

```kotlin
// Applied in synanton.java-conventions convention plugin:
checkstyle {
    configFile = rootProject.file("checkstyle.xml")
    maxWarnings = 0
    toolVersion = libs.versions.checkstyle.get()
}
```

Run: `./gradlew checkstyleMain checkstyleTest`

## JaCoCo Coverage

```kotlin
plugins { jacoco }

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    reports { xml.required = true }
    dependsOn(tasks.test)
}
```

Run: `./gradlew test jacocoTestReport`

## OWASP Dependency-Check

```kotlin
plugins { id("org.owasp.dependencycheck") }

dependencyCheck {
    failBuildOnCVSS = 7.0f
    analyzers.assemblyEnabled = false
}
```

Run: `./gradlew dependencyCheckAnalyze`
