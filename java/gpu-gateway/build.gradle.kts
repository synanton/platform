// gpu-gateway: GPU Execution Plane service.
// Implements synanton.gpu.v1.GPUExecutionService as a gRPC server.
// Depends on gpu-contract for generated stubs; MUST NOT depend on synanton/platform internals.

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    java
}

dependencies {
    implementation(project(":java:synanton-llm-client"))
    implementation(project(":java:gpu-contract"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.mustache.compiler)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Add gRPC and Protocol Buffers support
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)
    compileOnly(libs.javax.annotation)

    testImplementation(libs.spring.boot.starter.test)
}
