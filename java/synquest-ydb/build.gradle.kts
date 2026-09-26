plugins {
    `java-library`
}

dependencies {
    api(project(":java:synquest-api"))
    api(libs.ydb.sdk.table)

    testImplementation(project(":java:synquest-api"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.logback.classic)
}
