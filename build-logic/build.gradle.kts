plugins {
    `kotlin-dsl`
}

// Resolves a catalog plugin alias to its plugin-marker coordinate, so no version literal appears here.
fun marker(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

dependencies {
    implementation(marker(libs.plugins.spotless.plugin))
    implementation(marker(libs.plugins.spring.boot.plugin))
}
