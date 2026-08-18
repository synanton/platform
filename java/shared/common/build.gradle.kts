plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.nimbus.jose.jwt)
    api(libs.slf4j.api)
    api(libs.owasp.java.html.sanitizer)
    api(libs.micrometer.core)

    compileOnly(libs.jakarta.servlet.api)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.validation)
    compileOnly(libs.spring.boot.starter.actuator)
    compileOnly(libs.kafka.clients)
    compileOnly(libs.grpc.api)
    compileOnly(libs.pgv.java.stub)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.grpc.api)
    testImplementation(libs.pgv.java.stub)

    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
}
