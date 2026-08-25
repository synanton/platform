// extraction-contract: slim library module that owns the synanton.extraction.v1
// protobuf contract on the platform side.
//
// The .proto files here are a byte-identical mirror of
// content_extractor/java/extraction-contract/src/main/proto/. The mirror is
// enforced by `verifyContractMirror` (scripts/verify-contract-mirror.sh), wired
// into `check`. Do not edit one copy without the other.
//
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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.grpc.inprocess)
}

protobuf {
    protoc {
        artifact = libs.protoc.asProvider().get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.protoc.gen.grpc.java.get().toString()
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

// The mirror is part of correctness, not a separate chore: a contract that differs
// between repositories is not one contract.
tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyContractMirror"))
}
