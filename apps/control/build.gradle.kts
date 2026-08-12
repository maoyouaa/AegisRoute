plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:contracts"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.postgresql:postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("application.jar")
}

tasks.named<Test>("integrationTest") {
    maxParallelForks = 1
}
