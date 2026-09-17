// Shared by every Java module: toolchain, compiler flags, formatting and test setup (C5 #ct5-modules,
// build-logic row). Internal — a module applies exactly one dlock.*-conventions plugin, never this one.

plugins {
    java
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

spotless {
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
        licenseHeaderFile(rootProject.file("config/spotless/license-header.txt"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

// The catalog keeps junit-bom and assertj at the versions the Spring Boot BOM manages, so the services
// and the other modules test against the same ones.
dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findBundle("test").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Lease-timing tests are wall-clock sensitive: one fork, never one per available processor.
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
    }
}

// The deterministic simulation suite is the project's cheap correctness signal and runs on every pull
// request (T-006). It is its own task, and excluded from `test`, so a unit run stays fast and a
// simulation failure reports as itself. It must exit 0 while M4 has not written a single
// *SimulationTest yet (T-043 writes the first): a task that failed on an empty set would block all of
// M0. The configureEach block above already gives it the JUnit platform and the single fork.
// Resolved here, not inside the task block: there the receiver is the task, and `the<…>()` would look
// the extension up on the task instead of the project.
val testSources = the<SourceSetContainer>()["test"]

val simulationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the deterministic simulation tests (*SimulationTest). Green on an empty set."
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    filter {
        includeTestsMatching("*SimulationTest")
        isFailOnNoMatchingTests = false
    }
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("*SimulationTest")
        // Gradle treats an exclusion as a filter, and fails with "No tests found for given includes"
        // once it removes a module's last test class -- which is exactly what happens to a module whose
        // only tests are simulations (harness, after T-043). This does not weaken any assertion: what it
        // gives up is the error on a mistyped command-line `--tests` pattern, which then reports zero
        // tests run instead of failing. CI runs the whole suite, so the typo cannot hide there.
        isFailOnNoMatchingTests = false
    }
}
