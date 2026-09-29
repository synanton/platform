plugins {
    `java-library`
}

dependencies {
    // PG-POC-004: the adapter exists now. Driver stays an api dependency of
    // this module only (never leaks to domain modules — PG-POC-000 guards).
    api(project(":java:synvault-api"))
    api(libs.postgresql)
    testImplementation(project(":java:storage-provider"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.postgresql)
}
