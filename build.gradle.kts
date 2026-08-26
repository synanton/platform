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
// the content_extractor repository. Skips (does not fail) when the peer repo is absent.
tasks.register<Exec>("verifyContractMirror") {
    group = "verification"
    description = "Verify the extraction contract matches the content_extractor repository copy"
    commandLine("./scripts/verify-contract-mirror.sh")
    isIgnoreExitValue = false
}

tasks.register<Exec>("verifyGpuContractMirror") {
    group = "verification"
    description = "Verify the GPU contract matches the gpu-runtime repository copy"
    commandLine("./scripts/verify-gpu-contract-mirror.sh")
    isIgnoreExitValue = false
}

tasks.named("check") {
    dependsOn("verifyContractMirror", "verifyGpuContractMirror")
}
