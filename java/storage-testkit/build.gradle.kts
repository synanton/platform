plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":java:synvault-api"))
    api(project(":java:synquest-api"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
