import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// Align Testcontainers with the version catalog instead of Spring Boot's older
// managed version; the Cassandra test uses the 1.20+ module API.
dependencyManagement {
    dependencies {
        dependency("org.testcontainers:testcontainers:1.21.4")
        dependency("org.testcontainers:junit-jupiter:1.21.4")
        dependency("org.testcontainers:cassandra:1.21.4")
        dependency("org.testcontainers:database-commons:1.21.4")
    }
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:ingestion-cache"))
    implementation(project(":java:content-cache-api"))
    implementation(project(":java:synvault"))
    implementation(project(":java:synanton-llm-client"))
    implementation(project(":java:gpu-client"))
    implementation(project(":java:extraction-client"))
    implementation(project(":java:extraction-engine"))
    implementation(project(":java:eventing-contract"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.prometheus)
    implementation(libs.mustache.compiler)
    implementation(libs.caffeine)
    implementation(libs.pdfbox)
    implementation(libs.logback.classic)
    implementation(libs.kafka.clients)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.cassandra)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("synflux")
}
tasks.named<Jar>("jar") { enabled = false }

tasks.register<JavaExec>("gpuSemanticEvaluation") {
    group = "verification"
    description = "Run the gateway-compliant IT Books semantic extraction evaluation"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.synanton.synflux.evaluation.GpuSemanticEvaluation")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("regexBaselineSmoke") {
    group = "verification"
    description = "Run the authorized regex-only IT Books baseline smoke"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.synanton.synflux.evaluation.RegexBaselineSmoke")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("a4RegexBaseline") {
    group = "verification"
    description = "Run the reproducible A4 regex baseline evaluation"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.synanton.synflux.evaluation.RegexBaselineA4")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("a8ResolutionEvaluation") {
    group = "verification"
    description = "Run the A8.1 isolated resolution evaluation"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.synanton.synflux.evaluation.A8ResolutionEvaluation")
    workingDir = rootProject.projectDir
}
