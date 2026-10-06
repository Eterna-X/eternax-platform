// Shared Spring starter used by every servlet-based service: request context, error mapping,
// transactional outbox, Kafka wiring, distributed lock, idempotency store and resilient calls.
dependencies {
    api("com.eternax:eternax-core:${property("eternaxCoreVersion")}")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.kafka:spring-kafka")
    api("io.micrometer:micrometer-registry-prometheus")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")
    api("io.github.resilience4j:resilience4j-circuitbreaker")
    api("io.github.resilience4j:resilience4j-retry")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":eternax-test-support"))
}
