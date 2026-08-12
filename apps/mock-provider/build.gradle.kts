plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:contracts"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("io.projectreactor:reactor-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("application.jar")
}
