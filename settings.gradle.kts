// The module registry: the only place a module is declared (ADR-010 D2, C5 #ct5-modules).
// Exactly one version catalog, `libs`, is read from gradle/libs.versions.toml by Gradle's default
// convention (C5 #ct5-catalog). Do not declare it again here: a second `from(...)` on `libs` fails.

pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "distributed-lock-lab"

include(
    "lock-api",
    "lock-server",
    "lock-client",
    "payment-resource",
    "payout-executor",
    "rail-proxy",
    "rail-stub",
    "harness",
    "deploy",
)
