plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// 028a.7 holds the full 160k×384 corpus in memory (~500MB vectors + texts):
// 2g is a documented requirement, not tuning. The 028a.10 emitter streams to
// disk and must not need this.
tasks.named<Test>("test") {
    maxHeapSize = "2g"
}
