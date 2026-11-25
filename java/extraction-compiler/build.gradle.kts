plugins {
    java
    application
}

dependencies {
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

application {
    mainClass.set("org.synanton.extraction.compiler.Main")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
