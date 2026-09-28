plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(project(":java:synquest-api"))
    testImplementation(project(":java:storage-provider"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.lucene.core)
    testImplementation(libs.lucene.analysis.common)
    testImplementation(libs.lucene.queryparser)
}
