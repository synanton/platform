plugins {
    `java-library`
}

dependencies {
    // PG-POC-007: retrieval adapter. Driver stays an api dependency of this
    // module only (never leaks to domain modules — PG-POC-000 guards).
    api(project(":java:synquest-api"))
    api(libs.postgresql)
    testImplementation(project(":java:synquest-api"))
    testImplementation(project(":java:storage-provider"))
    // Schema DDL lives in synvault-postgres (DDL-in-one-place): the quest
    // fixture installs it via PostgresSchema, never a local copy.
    testImplementation(project(":java:synvault-postgres"))
    // 007-8 emitter wiring proof: PG Q3 output must satisfy the shared
    // emitter contract (RunOutput strict parser). Jackson stays test-scope:
    // the production engine never serializes JSON.
    testImplementation(project(":java:synanton-bench-convergence"))
    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.postgresql)
}
