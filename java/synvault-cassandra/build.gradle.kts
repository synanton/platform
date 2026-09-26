plugins {
    `java-library`
}

dependencies {
    api(project(":java:synvault-api"))
    api(project(":java:ingestion-cache"))

    testImplementation(project(":java:synvault-api"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.cassandra.driver.core)
}
