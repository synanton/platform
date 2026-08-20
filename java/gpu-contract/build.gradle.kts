// gpu-contract: slim library module that owns the synanton.gpu.v1 protobuf contract.
// Both gpu-gateway (server) and gateway (client) depend on this module for generated stubs.
// Nothing in this module may depend on synanton/platform internals.

plugins {
    java
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    compileOnly(libs.javax.annotation)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.grpc.inprocess)
}

protobuf {
    protoc {
        // Use the version from the catalog
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            // If you have a grpc version defined, use it; otherwise specify the version directly
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
            // or hardcode if no version entry: "io.grpc:protoc-gen-grpc-java:1.54.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") { }
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
