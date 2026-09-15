// Root build: no versions, no dependencies, no per-module logic (ADR-010 D2).
// Convention plugins from build-logic are applied once T-003 lands.

// Core plugin, no version: provides a real `build` lifecycle task. Without it, `./gradlew build`
// silently resolves to the `buildEnvironment` help task by abbreviation and builds nothing.
plugins {
    base
}
