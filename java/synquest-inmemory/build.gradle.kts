plugins {
    `java-library`
}

dependencies {
    api(project(":java:synquest-api"))

    testImplementation(project(":java:synquest-api"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
