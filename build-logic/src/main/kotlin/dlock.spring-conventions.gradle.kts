// The five Spring Boot services named in the spring-boot-plugin Used-by column (C5 #ct5-catalog):
// lock-server, payment-resource, payout-executor, rail-proxy and rail-stub.

plugins {
    id("dlock.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))
    implementation(libs.findBundle("service-observability").get())
}

// A service with no sources yet has no main class, and bootJar fails the build resolving one. Skip it
// until the service's first Java source lands (T-016 for lock-server); from then on it runs as normal.
val mainJava = the<SourceSetContainer>()["main"].allJava
tasks.named("bootJar") {
    onlyIf("the service has Java sources") { !mainJava.isEmpty }
}
