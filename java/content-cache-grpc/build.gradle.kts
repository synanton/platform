plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":java:content-cache-api"))
    implementation(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)
    compileOnly(libs.javax.annotation)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.protobuf.java)
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

tasks.test {
    useJUnitPlatform()
}
