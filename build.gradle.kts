import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    jacoco
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.2.1" apply false
}

group = "io.github.maoyouaa"
val releaseVersion = providers.gradleProperty("projectVersion").get()
require(releaseVersion.matches(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?"""))) {
    "projectVersion must be a URL-safe semantic version, but was '$releaseVersion'"
}
version = releaseVersion

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    group = rootProject.group
    version = rootProject.version

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
            exceptionFormat = TestExceptionFormat.FULL
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.28.0")
            target("src/**/*.java")
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            ktlint("1.7.1")
            target("*.gradle.kts", "**/*.gradle.kts")
        }
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
    val integrationTestSourceSet = sourceSets.create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }

    configurations[integrationTestSourceSet.implementationConfigurationName]
        .extendsFrom(configurations["testImplementation"])
    configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
        .extendsFrom(configurations["testRuntimeOnly"])

    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.named("test"))
    }

    tasks.named("check") {
        dependsOn(tasks.named("spotlessCheck"))
    }
}

tasks.register("integrationTest") {
    description = "Runs all integration test suites."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(subprojects.map { it.tasks.named("integrationTest") })
}

val verifySupplyChainWorkflow = tasks.register("verifySupplyChainWorkflow") {
    description = "Verifies least-privilege invariants for the supply-chain workflow."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    val workflow = layout.projectDirectory.file(".github/workflows/supply-chain.yml")
    inputs.file(workflow)

    doLast {
        val contents = workflow.asFile.readText()
        val pinnedGitleaksStep =
            """
            |      - uses: gitleaks/gitleaks-action@ff98106e4c7b2bc287b24eaf42907196329070c7 # v2.3.9
            |        env:
            |          GITHUB_TOKEN: ${'$'}{{ github.token }}
            |          GITLEAKS_ENABLE_COMMENTS: "false"
            """.trimMargin()

        check(contents.contains("permissions:\n  contents: read")) {
            "Supply-chain workflow must default to read-only repository contents."
        }
        check(!Regex("""(?m)^\s+[A-Za-z-]+:\s+write\s*$""").containsMatchIn(contents)) {
            "Supply-chain workflow must not grant write permissions."
        }
        check(contents.contains(pinnedGitleaksStep)) {
            "Gitleaks must stay SHA-pinned, receive only the step-scoped GitHub token, and disable PR comments."
        }
        check(Regex("""persist-credentials:\s+false""").findAll(contents).count() == 2) {
            "Both supply-chain checkout steps must avoid persisting credentials."
        }
    }
}

val verifyReleaseWorkflowSecurity = tasks.register("verifyReleaseWorkflowSecurity") {
    description = "Verifies the static least-privilege boundary for the release workflow."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    val workflow = layout.projectDirectory.file(".github/workflows/release.yml")
    inputs.file(workflow)

    doLast {
        val contents = workflow.asFile.readText().replace("\r\n", "\n")
        val actionReferences =
            Regex("""(?m)^\s+(?:-\s+)?uses:\s+([^#\s]+)""")
                .findAll(contents)
                .map { it.groupValues[1] }
                .toList()
        val unpinnedActions =
            actionReferences.filterNot {
                Regex("""^[^@\s]+@[0-9a-f]{40}${'$'}""").matches(it)
            }

        check(actionReferences.isNotEmpty() && unpinnedActions.isEmpty()) {
            "Every third-party release Action must use a full commit SHA; unpinned: $unpinnedActions"
        }
        check(contents.contains("permissions:\n  contents: read\n\njobs:")) {
            "Release workflow must default to read-only repository contents."
        }

        val writePermissions =
            Regex("""(?m)^\s{6}([a-z-]+): write\s*${'$'}""")
                .findAll(contents)
                .map { it.groupValues[1] }
                .toList()
        check(
            writePermissions ==
                listOf(
                    "contents",
                    "pull-requests",
                    "issues",
                    "packages",
                    "id-token",
                    "attestations",
                )
        ) {
            "Release jobs may grant only the reviewed job-scoped write permissions; found $writePermissions"
        }

        check(
            contents.contains(
                "if: needs.release-please.outputs.release_created == 'true'"
            )
        ) {
            "Image publication must stay gated on Release Please reporting release_created=true."
        }
        check(
            contents.contains(
                "release_created: ${'$'}{{ steps.release.outputs.release_created }}"
            )
        ) {
            "Release Please must expose the release_created output used by the publication gate."
        }

        val checkoutCount = actionReferences.count { it.startsWith("actions/checkout@") }
        val nonPersistentCheckoutCount =
            Regex("""(?m)^\s+persist-credentials:\s+false\s*${'$'}""")
                .findAll(contents)
                .count()
        check(checkoutCount > 0 && checkoutCount == nonPersistentCheckoutCount) {
            "Every release checkout must set persist-credentials=false."
        }
        check(contents.contains("ref: ${'$'}{{ needs.release-please.outputs.sha }}")) {
            "Release images must build from the exact SHA emitted by Release Please."
        }

        val referencedSecrets =
            Regex("""\$\{\{\s*secrets\.([A-Za-z0-9_]+)\s*}}""")
                .findAll(contents)
                .map { it.groupValues[1] }
                .toSet()
        check(referencedSecrets == setOf("GITHUB_TOKEN")) {
            "Release workflow may reference only the ephemeral GITHUB_TOKEN; found $referencedSecrets"
        }
        check(!contents.contains("secrets[")) {
            "Dynamic secret lookup is not allowed in the release workflow."
        }
        check(!Regex("""(?m)^\s+(?:github-)?token:\s+""").containsMatchIn(contents)) {
            "Release Please must use the default GITHUB_TOKEN rather than a custom token input."
        }
    }
}

tasks.named("check") {
    dependsOn(verifySupplyChainWorkflow)
    dependsOn(verifyReleaseWorkflowSecurity)
}
