plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":libs:service-support"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.webmvc.ui)
    implementation(project(":libs:asp-adapters"))
    implementation(libs.spring.kafka)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
