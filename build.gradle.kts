// Root build: no versions, no dependencies, no per-module logic (ADR-010 D2).
// Convention plugins from build-logic are applied once T-003 lands.

// Core plugin, no version: provides a real `build` lifecycle task. Without it, `./gradlew build`
// silently resolves to the `buildEnvironment` help task by abbreviation and builds nothing.
plugins {
    base
}

// TEMPORARY CI shim — T-003 replaces this file and removes it. `.github/workflows/build.yml` runs
// `spotlessCheck` and `:lock-api:dependencies --configuration runtimeClasspath`; neither exists until
// T-003 applies Spotless and the Java plugins, so both are stubbed here and check nothing.
tasks.register("spotlessCheck") {
    group = "verification"
    description = "Placeholder until T-003 applies Spotless; checks nothing."
}

project(":lock-api") {
    configurations.resolvable("runtimeClasspath")
}
