plugins {
    `java-library`
}

dependencies {
    // VEC-B3.3: standalone Lucene vector adapter (no Cassandra storage).
    api(project(":java:synquest-api"))
    api(libs.lucene.core)
    api(libs.lucene.analysis.common)
    testImplementation(project(":java:synquest-api"))
    testImplementation(project(":java:synquest-cassandra"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(project(":java:synquest-cassandra"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
