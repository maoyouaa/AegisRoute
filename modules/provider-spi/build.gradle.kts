plugins {
    `java-library`
}

dependencies {
    api(project(":modules:contracts"))
    api("io.projectreactor:reactor-core")
    implementation("org.springframework:spring-webflux")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("io.projectreactor:reactor-test")
}
