pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "EngineHub"
            url = uri("https://maven.enginehub.org/repo/")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven {
            name = "EngineHub"
            url = uri("https://maven.enginehub.org/repo/")
        }
        mavenCentral()
    }
}

rootProject.name = "worldguard"
