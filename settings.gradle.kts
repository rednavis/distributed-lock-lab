// The module registry: the only place a module is declared (ADR-010 D2, C5 #ct5-modules).
// The version catalog gradle/libs.versions.toml (T-002) is picked up by Gradle's default
// convention as `libs`; no explicit wiring is needed here.

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
