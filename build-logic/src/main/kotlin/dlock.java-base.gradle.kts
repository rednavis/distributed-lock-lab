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
