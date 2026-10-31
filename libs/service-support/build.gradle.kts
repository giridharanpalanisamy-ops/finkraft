// Shared Spring wiring for services: Kafka error handling and topics, event publishing, structured logs.
dependencies {
    api(project(":libs:event-contracts"))
    api(libs.spring.boot.autoconfigure)
    api(libs.micrometer.prometheus)
    api("org.slf4j:slf4j-api")
    compileOnly(libs.spring.kafka)
}
