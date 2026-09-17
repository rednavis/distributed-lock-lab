// Applied by the root project only (C5 #ct5-layout: the root build applies convention plugins and
// nothing else). Formats the Gradle scripts that belong to no module: the root build and settings,
// and build-logic itself. Each module's own build.gradle.kts is covered by dlock.java-base.

plugins {
    // Core plugin, no version: provides a real `build` lifecycle task. Without it, `./gradlew build`
    // silently resolves to the `buildEnvironment` help task by abbreviation and builds nothing.
    base
    id("com.diffplug.spotless")
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
