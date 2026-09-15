// Java modules that may use Lombok: every one except lock-api (C5 #ct5-catalog, lombok row;
// C2 #ct2-zero-dep keeps lock-api free of it via dlock.api-conventions). Lombok is limited to
// @RequiredArgsConstructor and @Slf4j (C5 #ct5-modules), enforced by checkLombokPolicy below.

plugins {
    id("dlock.java-base")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val lombok = libs.findLibrary("lombok").get()

dependencies {
    compileOnly(lombok)
    annotationProcessor(lombok)
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)
}

// With Lombok on the processor path, javac's `processing` lint reports every annotation no processor
// claims (@Test, Spring's own) and -Werror fails the build on it. This is the only lint category
// turned off, and only in modules that run Lombok; -Werror itself stays on.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
}

val checkLombokPolicy by tasks.registering {
    group = "verification"
    description = "Fails on any use of Lombok other than @RequiredArgsConstructor and @Slf4j."
    val allowed = setOf("lombok.RequiredArgsConstructor", "lombok.extern.slf4j.Slf4j")
    val usage = Regex("""\blombok(?:\.(?:\w+|\*))+""")
    val sources = fileTree("src") { include("**/*.java") }
    val base = projectDir
    inputs.files(sources)
    doLast {
        val violations = sources.files.sorted().flatMap { file ->
            file.readLines().flatMapIndexed { index, line ->
                usage.findAll(line).map { it.value }.filter { it !in allowed }
                    .map { "${file.relativeTo(base)}:${index + 1}: $it" }.toList()
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Only @RequiredArgsConstructor and @Slf4j may be used from Lombok:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkLombokPolicy)
}
