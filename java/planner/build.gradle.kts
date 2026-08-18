plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    java
}
dependencies {
    implementation(project(":java:synanton-llm-client"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
}
