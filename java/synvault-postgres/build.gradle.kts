plugins {
    `java-library`
}

dependencies {
    // Main stays dependency-free (JDK + java.sql only): the domain boundary
    // guards (PG-POC-000) forbid driver/extension references outside adapters,
    // and the adapter itself does not exist yet (Phase 1).
    testImplementation(project(":java:synvault-api"))
    testImplementation(project(":java:storage-provider"))
    testImplementation(libs.postgresql)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.postgresql)
}
