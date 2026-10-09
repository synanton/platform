plugins {
    `java-library`
}

dependencies {
    // VEC-B3.2: Qdrant vector adapter. SDK stays an api dependency of this
    // module only (never leaks to domain modules — same rule as PG-POC-007).
    api(project(":java:synquest-api"))
    api(libs.qdrant.client)
    testImplementation(project(":java:synquest-api"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
