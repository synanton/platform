// gpu-gateway: GPU Execution Plane service.
// Implements synanton.gpu.v1.GPUExecutionService as a gRPC server.
// Depends on gpu-contract for generated stubs; MUST NOT depend on synanton/platform internals.

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    java
}

dependencies {
    implementation(project(":java:gpu-contract"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    compileOnly(libs.javax.annotation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.assertj.core)
}
