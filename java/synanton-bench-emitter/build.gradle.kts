plugins {
    `java-library`
}

dependencies {
    // Shared emitter: port APIs only. No adapter imports — 028c/d/e wire
    // adapters against this module; adapter-specific logic here is drift.
    api(project(":java:synquest-api"))
    api(libs.jackson.databind)
    testImplementation(project(":java:synquest-inmemory"))
    testImplementation(project(":java:synvault-inmemory"))
    testImplementation(project(":java:synanton-bench-convergence"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
