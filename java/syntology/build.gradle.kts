import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":java:shared:common"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    implementation(libs.jena.arq)
    implementation(libs.jena.tdb2)
    implementation(libs.jena.shacl)
    implementation(libs.hcl4j)

    implementation(libs.caffeine)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("syntology-demo")
}

tasks.named<Jar>("jar") {
    enabled = false
}

val skipUi: Boolean = project.hasProperty("skipUi")

val uiDir = rootProject.file("ui/syntology-admin")
val uiDistDir = uiDir.resolve("dist")
val staticDir = file("src/main/resources/static")

tasks.register("buildUi") {
    group = "synanton"
    description = "Build syntology-admin SPA and copy to static resources"
    onlyIf { !skipUi && uiDir.resolve("package.json").exists() }
    doLast {
        val nodePath = listOf(
            "/Applications/Cursor.app/Contents/Resources/app/resources/helpers",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            System.getenv("PATH") ?: "",
        ).joinToString(":")
        exec {
            workingDir = uiDir
            environment("PATH", nodePath)
            commandLine(
                "sh", "-c",
                "if command -v pnpm >/dev/null; then pnpm install && pnpm build; else npm install && npm run build; fi",
            )
        }
        copy {
            from(uiDistDir)
            into(staticDir)
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    if (!skipUi) {
        dependsOn("buildUi")
    }
}
