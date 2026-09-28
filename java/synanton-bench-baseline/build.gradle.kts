plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(project(":java:synquest-api"))
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    testImplementation(project(":java:storage-provider"))
    testImplementation(project(":java:synanton-bench-emitter"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.lucene.core)
    testImplementation(libs.lucene.analysis.common)
    testImplementation(libs.lucene.queryparser)
}

// 028b.2: 1g heap ceiling enforced as config — the whole suite (incl. the
// full-corpus streaming load) runs under it. Streaming, not heap, is the
// mechanism; this line proves the bound.
tasks.named<Test>("test") {
    maxHeapSize = "1g"
}
