plugins {
    `java-library`
}

dependencies {
    api(project(":java:storage-contract"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.datatype.jdk8)
}
