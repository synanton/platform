plugins {
    `java-library`
}

dependencies {
    api(libs.cassandra.driver.core)
    api(libs.spring.boot.starter.web) // for @ConfigurationProperties
    implementation(libs.caffeine)
    implementation(libs.lz4.java)
    implementation(libs.logback.classic)
    implementation(libs.jackson.databind)
    // Kafka producer used by OutboxPublisher; compileOnly so services without Kafka still build.
    compileOnly(libs.kafka.clients)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.bom)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.cassandra)
}

tasks.test {
    useJUnitPlatform()
}
