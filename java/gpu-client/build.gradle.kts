// Shared synanton.gpu.v1 client pieces: mTLS channel, canonical error codes, the EMBED
// payload codec, and GpuPlaneEmbedClient (a fail-closed, tenant-aware LlmClient).
// gateway, synquest and synflux use these (retrieval benchmark plan §6 Phase B1-G, G1).

plugins {
    `java-library`
}

dependencies {
    api(project(":java:synanton-llm-client"))
    api(project(":java:gpu-contract"))
    api(libs.grpc.stub)
    api(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
