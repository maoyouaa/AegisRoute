plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:contracts"))
    implementation(project(":modules:provider-spi"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:kafka:1.21.4")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("application.jar")
}
