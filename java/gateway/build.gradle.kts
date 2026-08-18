plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    java
}

dependencies {
    implementation(project(":java:synanton-llm-client"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.mustache.compiler)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
}
