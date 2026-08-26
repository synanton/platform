plugins {
    `java-library`
}

dependencies {
    api(project(":java:extraction-contract"))

    api(libs.spring.boot.starter.web)
    api(libs.grpc.netty.shaded)
    api(libs.grpc.stub)
    api(libs.protobuf.java)
    api(libs.micrometer.core)
    api(libs.slf4j.api)

    implementation(libs.tika.core)
    implementation(libs.tika.parsers)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
}

tasks.test {
    useJUnitPlatform()
}
