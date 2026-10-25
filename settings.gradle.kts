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
        maven {
            name = "EssentialsX"
            url = uri("https://repo.essentialsx.net/releases/")
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeModule("com.github.LeonMangler", "PremiumVanishAPI")
                includeModule("com.github.Zrips", "CMI-API")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "worldguard"
