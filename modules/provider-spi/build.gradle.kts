plugins {
    `java-library`
}

dependencies {
    api(project(":modules:domain"))
    api(project(":modules:contracts"))
    api("io.projectreactor:reactor-core")
    implementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor:reactor-test")
}
