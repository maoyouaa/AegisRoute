import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.4")
    }
}

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
        fun yamlMap(vararg entries: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*entries)

        fun normalizeYaml(value: Any?, root: Boolean = false): Any? =
            when (value) {
                is Map<*, *> -> {
                    val normalized = linkedMapOf<String, Any?>()
                    value.forEach { (rawKey, rawValue) ->
                        val key =
                            when {
                                rawKey is String -> rawKey
                                root && rawKey == true -> "on"
                                else -> error("Release workflow contains a non-string YAML key: $rawKey")
                            }
                        check(!normalized.containsKey(key)) {
                            "Release workflow contains a duplicate normalized YAML key: $key"
                        }
                        normalized[key] = normalizeYaml(rawValue)
                    }
                    normalized
                }

                is List<*> -> value.map { normalizeYaml(it) }
                else -> value
            }

        fun firstDifference(expected: Any?, actual: Any?, path: String = "workflow"): String? {
            if (expected is Map<*, *> && actual is Map<*, *>) {
                if (expected.keys != actual.keys) {
                    return "$path keys expected ${expected.keys} but found ${actual.keys}"
                }
                expected.keys.forEach { key ->
                    firstDifference(expected[key], actual[key], "$path.$key")?.let { return it }
                }
                return null
            }
            if (expected is List<*> && actual is List<*>) {
                if (expected.size != actual.size) {
                    return "$path expected ${expected.size} entries but found ${actual.size}"
                }
                expected.indices.forEach { index ->
                    firstDifference(expected[index], actual[index], "$path[$index]")?.let { return it }
                }
                return null
            }
            return if (expected == actual) {
                null
            } else {
                "$path expected '$expected' but found '$actual'"
            }
        }

        val expectedTags =
            "ghcr.io/${'$'}{{ github.repository_owner }}/aegisroute-${'$'}{{ matrix.app }}:" +
                "${'$'}{{ needs.release-please.outputs.tag_name }}\n" +
                "ghcr.io/${'$'}{{ github.repository_owner }}/aegisroute-${'$'}{{ matrix.app }}:latest\n"
        val expectedWorkflow =
            yamlMap(
                "name" to "Release",
                "on" to yamlMap("push" to yamlMap("branches" to listOf("main"))),
                "permissions" to yamlMap("contents" to "read"),
                "jobs" to
                    yamlMap(
                        "release-please" to
                            yamlMap(
                                "runs-on" to "ubuntu-24.04",
                                "permissions" to
                                    yamlMap(
                                        "contents" to "write",
                                        "pull-requests" to "write",
                                        "issues" to "write",
                                    ),
                                "outputs" to
                                    yamlMap(
                                        "release_created" to
                                            "${'$'}{{ steps.release.outputs.release_created }}",
                                        "tag_name" to "${'$'}{{ steps.release.outputs.tag_name }}",
                                        "sha" to "${'$'}{{ steps.release.outputs.sha }}",
                                    ),
                                "steps" to
                                    listOf(
                                        yamlMap(
                                            "id" to "release",
                                            "uses" to
                                                "googleapis/release-please-action@" +
                                                "45996ed1f6d02564a971a2fa1b5860e934307cf7",
                                            "with" to
                                                yamlMap(
                                                    "config-file" to "release-please-config.json",
                                                    "manifest-file" to ".release-please-manifest.json",
                                                ),
                                        )
                                    ),
                            ),
                        "images" to
                            yamlMap(
                                "needs" to "release-please",
                                "if" to
                                    "needs.release-please.outputs.release_created == 'true'",
                                "runs-on" to "ubuntu-24.04",
                                "permissions" to
                                    yamlMap(
                                        "contents" to "read",
                                        "packages" to "write",
                                        "id-token" to "write",
                                        "attestations" to "write",
                                    ),
                                "strategy" to
                                    yamlMap(
                                        "matrix" to
                                            yamlMap(
                                                "app" to
                                                    listOf(
                                                        "gateway",
                                                        "control",
                                                        "worker",
                                                        "mock-provider",
                                                    )
                                            )
                                    ),
                                "steps" to
                                    listOf(
                                        yamlMap(
                                            "uses" to
                                                "actions/checkout@" +
                                                "11bd71901bbe5b1630ceea73d27597364c9af683",
                                            "with" to
                                                yamlMap(
                                                    "ref" to
                                                        "${'$'}{{ needs.release-please.outputs.sha }}",
                                                    "persist-credentials" to false,
                                                ),
                                        ),
                                        yamlMap(
                                            "uses" to
                                                "docker/login-action@" +
                                                "184bdaa0721073962dff0199f1fb9940f07167d1",
                                            "with" to
                                                yamlMap(
                                                    "registry" to "ghcr.io",
                                                    "username" to "${'$'}{{ github.actor }}",
                                                    "password" to "${'$'}{{ secrets.GITHUB_TOKEN }}",
                                                ),
                                        ),
                                        yamlMap(
                                            "id" to "build",
                                            "uses" to
                                                "docker/build-push-action@" +
                                                "263435318d21b8e681c14492fe198d362a7d2c83",
                                            "with" to
                                                yamlMap(
                                                    "context" to ".",
                                                    "file" to "deployment/Dockerfile",
                                                    "build-args" to "APP=${'$'}{{ matrix.app }}",
                                                    "push" to true,
                                                    "tags" to expectedTags,
                                                ),
                                        ),
                                        yamlMap(
                                            "uses" to
                                                "actions/attest-build-provenance@" +
                                                "977bb373ede98d70efdf65b84cb5f73e068dcc2a",
                                            "with" to
                                                yamlMap(
                                                    "subject-name" to
                                                        "ghcr.io/${'$'}{{ github.repository_owner }}/" +
                                                        "aegisroute-${'$'}{{ matrix.app }}",
                                                    "subject-digest" to
                                                        "${'$'}{{ steps.build.outputs.digest }}",
                                                    "push-to-registry" to true,
                                                ),
                                        ),
                                        yamlMap(
                                            "uses" to
                                                "anchore/sbom-action@" +
                                                "f8bdd1d8ac5e901a77a92f111440fdb1b593736b",
                                            "with" to
                                                yamlMap(
                                                    "image" to
                                                        "ghcr.io/${'$'}{{ github.repository_owner }}/" +
                                                        "aegisroute-${'$'}{{ matrix.app }}@" +
                                                        "${'$'}{{ steps.build.outputs.digest }}",
                                                    "artifact-name" to
                                                        "${'$'}{{ matrix.app }}-" +
                                                        "${'$'}{{ needs.release-please.outputs.tag_name }}" +
                                                        ".spdx.json",
                                                ),
                                        ),
                                    ),
                            ),
                    ),
            )

        fun validateReleaseWorkflowSecurity(contents: String) {
            check(Regex("""(?m)^on:[ \t]*(?:#.*)?${'$'}""").findAll(contents).count() == 1) {
                "Release workflow must declare one canonical top-level on key."
            }
            check(
                Regex("""(?m)^[ \t]*permissions:[ \t]*(?:#.*)?${'$'}""")
                    .findAll(contents)
                    .count() == 3
            ) {
                "Release workflow must declare three canonical block-form permission maps."
            }
            val loaderOptions =
                LoaderOptions().apply {
                    setAllowDuplicateKeys(false)
                    setAllowRecursiveKeys(false)
                    setMaxAliasesForCollections(0)
                    setCodePointLimit(200_000)
                }
            val documents = Yaml(SafeConstructor(loaderOptions)).loadAll(contents).toList()
            check(documents.size == 1) {
                "Release workflow must contain exactly one YAML document."
            }
            val actualWorkflow = normalizeYaml(documents.single(), root = true)
            check(actualWorkflow is Map<*, *>) {
                "Release workflow root must be a YAML mapping."
            }
            val difference = firstDifference(expectedWorkflow, actualWorkflow)
            check(difference == null) {
                "Release workflow must match the reviewed least-privilege structure: $difference"
            }
        }

        val contents = workflow.asFile.readText().replace("\r\n", "\n")
        validateReleaseWorkflowSecurity(contents)

        val forbiddenMutations =
            mapOf(
                "an additional pinned approval Action" to
                    contents.replaceFirst(
                        "    steps:\n",
                        "    steps:\n" +
                            "      - uses: example/auto-approve@" +
                            "0".repeat(40) +
                            "\n",
                    ),
                "an arbitrary merge command" to
                    contents.replaceFirst(
                        "    steps:\n",
                        "    steps:\n      - run: gh pr merge 8 --merge\n",
                    ),
                "publication permission on the Release Please job" to
                    contents.replaceFirst(
                        "      issues: write\n",
                        "      issues: write\n      packages: write\n",
                    ),
                "a duplicate write-all permission block" to
                    contents.replaceFirst(
                        "  release-please:\n",
                        "  release-please:\n    permissions: write-all\n",
                    ),
                "a pull_request_target trigger" to
                    contents.replaceFirst(
                        "on:\n  push:\n    branches: [main]",
                        "on:\n  pull_request_target:",
                    ),
                "a YAML 1.1 boolean trigger key" to
                    contents.replaceFirst(
                        "on:\n  push:\n    branches: [main]",
                        "true:\n  push:\n    branches: [main]",
                    ),
                "a flow-style auto-merge job with an underscore id" to
                    contents +
                    "\n  auto_merge:\n" +
                    "    runs-on: ubuntu-24.04\n" +
                    "    permissions : write-all\n" +
                    "    steps:\n" +
                    "      - { run : 'GH_TOKEN=${'$'}{{ secrets.GITHUB_TOKEN }} " +
                    "gh pr review 8 --approve && gh pr merge 8 --merge' }\n",
                "a duplicate publication gate" to
                    contents.replaceFirst(
                        "    if: needs.release-please.outputs.release_created == 'true'\n",
                        "    if: needs.release-please.outputs.release_created == 'true'\n" +
                            "    if: always()\n",
                    ),
                "a duplicate unsafe checkout ref" to
                    contents.replaceFirst(
                        "          ref: ${'$'}{{ needs.release-please.outputs.sha }}\n",
                        "          ref: ${'$'}{{ needs.release-please.outputs.sha }}\n" +
                            "          ref: main\n",
                    ),
                "checkout credentials moved to the login step" to
                    contents
                        .replaceFirst("          persist-credentials: false\n", "")
                        .replaceFirst(
                            "          registry: ghcr.io\n",
                            "          registry: ghcr.io\n" +
                                "          persist-credentials: false\n",
                        ),
            )
        forbiddenMutations.forEach { (description, mutated) ->
            check(runCatching { validateReleaseWorkflowSecurity(mutated) }.isFailure) {
                "Release workflow verifier accepted $description."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifySupplyChainWorkflow)
    dependsOn(verifyReleaseWorkflowSecurity)
}
