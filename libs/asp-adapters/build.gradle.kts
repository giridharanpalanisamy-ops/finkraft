dependencies {
    api(project(":libs:adapter-api"))
    compileOnly(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.autoconfigure)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.wiremock)
}
