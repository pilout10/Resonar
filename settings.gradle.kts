// Configuración del proyecto Resonar.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube) se publica por JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Resonar"
include(":app")
