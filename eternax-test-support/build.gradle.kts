// Test and local-development helpers: a real PostgreSQL and Kafka started in-process (no Docker needed).
dependencies {
    api("io.zonky.test:embedded-postgres:2.1.0")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.kafka:spring-kafka-test")
    implementation("org.postgresql:postgresql")
}

// ./gradlew :eternax-test-support:devInfra  -> PostgreSQL on :5432 and Kafka on :9092 until stopped
tasks.register<JavaExec>("devInfra") {
    group = "application"
    description = "Runs embedded PostgreSQL and Kafka for local development without Docker"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eternax.recon.testsupport.DevInfrastructure")
    standardInput = System.`in`
}
