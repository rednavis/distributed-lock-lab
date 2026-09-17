// Included build holding the convention plugins (C5 #ct5-modules, build-logic row). It reads the
// root version catalog, so plugin versions come from the one place they are pinned (C5 #ct5-catalog).

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
