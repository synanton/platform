plugins {
    `java-library`
}

dependencies {
    api(project(":java:storage-contract"))

    testImplementation(project(":java:synvault-inmemory"))
    testImplementation(project(":java:synquest-inmemory"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
