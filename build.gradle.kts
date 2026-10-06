// Spring starter shared by every servlet-based service (outbox, Kafka, errors, locks, idempotency, resilience)
// plus eternax-test-support (embedded PostgreSQL / Kafka for tests and local development).
plugins {
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.2.1" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    group = rootProject.group
    version = rootProject.version

    repositories {
        if (providers.gradleProperty("useMavenLocal").isPresent) mavenLocal()   // local verification only
        maven {
            name = "eternax-core"
            url = uri("https://maven.pkg.github.com/Eterna-X/eternax-core")
            credentials {
                username = System.getenv("PACKAGES_USER") ?: System.getenv("GITHUB_ACTOR")
                password = System.getenv("PACKAGES_TOKEN") ?: System.getenv("GITHUB_TOKEN")
            }
        }
        mavenCentral()
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16") }
        dependencies {
            dependency("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
            dependency("io.github.resilience4j:resilience4j-retry:2.3.0")
            dependency("io.github.resilience4j:resilience4j-timelimiter:2.3.0")
            dependency("io.github.resilience4j:resilience4j-micrometer:2.3.0")
            dependency("io.zonky.test:embedded-postgres:2.1.0")
        }
    }

    extensions.configure<JavaPluginExtension> { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.28.0").aosp().reflowLongStrings()
            removeUnusedImports()
        }
    }
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                versionMapping { allVariants { fromResolutionResult() } }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Eterna-X/eternax-platform")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
