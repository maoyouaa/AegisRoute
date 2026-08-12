plugins {
    `java-library`
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("contracts"))
    }
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.networknt:json-schema-validator:1.5.9")
}
