plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
