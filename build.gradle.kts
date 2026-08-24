plugins {
    java
}

allprojects {
    group = "org.synanton"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }
}

tasks.register("buildAll") {
    group = "synanton"
    description = "Build every active module"
    dependsOn(subprojects.map { it.tasks.named("build") })
}

// Fails when the mirrored synanton.extraction.v1 protos diverge from the copy in
// the content_extractor repository. The synanton.gpu.v1 pair drifted precisely
// because no such check existed: it is now one file vs four, org.* vs com.*, with
// different RPC names. Skips (does not fail) when the peer repo is absent.
tasks.register<Exec>("verifyContractMirror") {
    group = "verification"
    description = "Verify the extraction contract matches the content_extractor repository copy"
    commandLine("./scripts/verify-contract-mirror.sh")
    isIgnoreExitValue = false
}
