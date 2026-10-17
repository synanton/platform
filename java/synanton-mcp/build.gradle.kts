plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

tasks.named<Jar>("jar") {
    enabled = false
}

dependencies {
    implementation(project(":java:shared:common"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.caffeine)
    implementation(libs.jackson.databind)
    implementation(libs.logback.classic)
    implementation(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.networknt.validator)
    // Jackson 3.x (tools.jackson, via networknt) still reads
    // com.fasterxml.jackson.annotation, and needs JsonSerializeAs (>=2.22).
    // The Boot BOM pins annotations to 2.17.2, so force the test classpath up.
    testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.22")
    configurations.named("testRuntimeClasspath").configure {
        resolutionStrategy.force("com.fasterxml.jackson.core:jackson-annotations:2.22")
    }
}
