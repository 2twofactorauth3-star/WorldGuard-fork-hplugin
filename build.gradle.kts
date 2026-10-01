import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.github.johnrengelman:shadow:8.1.1")
        constraints {
            classpath("org.ow2.asm:asm:9.7")
            classpath("org.ow2.asm:asm-commons:9.7")
            classpath("org.vafer:jdependency:2.10")
        }
    }
}

plugins {
    `java-library`
}

apply(plugin = "com.github.johnrengelman.shadow")

group = "com.sk89q.worldguard"
version = "7.0.17"

base {
    archivesName.set("worldguard-bukkit")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.txt")) {
        into("META-INF")
    }
}

dependencies {
    api(libs.worldedit.core)
    api(libs.worldedit.bukkit) {
        isTransitive = false
    }

    implementation(libs.snakeyaml)
    implementation(libs.guava)
    implementation(libs.squirrelid) {
        isTransitive = false
    }
    implementation(libs.prtree)
    implementation(libs.bstats.bukkit)

    compileOnlyApi(libs.jsr305)
    compileOnly(libs.paperApi) {
        exclude("org.slf4j", "slf4j-api")
        exclude("junit", "junit")
        exclude(group = "org.apache.maven")
        exclude(group = "org.apache.maven.resolver")
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.hamcrest.library)
    testImplementation("net.kyori:adventure-text-minimessage:4.17.0")
    testImplementation("net.kyori:adventure-text-serializer-gson:4.17.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isDeprecation = true
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-Xlint:all",
        "-Xlint:-processing",
        "-Xlint:-path",
        "-Xlint:-fallthrough",
        "-Xlint:-serial",
        "-Xlint:-overloads",
    ))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    dependencies {
        include(dependency("org.enginehub:squirrelid"))
        include(dependency("org.khelekore:prtree"))
        include(dependency("org.bstats:bstats-base"))
        include(dependency("org.bstats:bstats-bukkit"))
    }
    relocate("org.enginehub.squirrelid", "com.sk89q.worldguard.util.profile")
    relocate("org.bstats", "com.sk89q.worldguard.internal.bstats")
    exclude("module-info.class")
    exclude("GradleStart**")
    exclude(".cache")
    exclude("META-INF/maven/**")
    manifest {
        attributes(
            "Implementation-Version" to project.version,
            "License" to "GNU Lesser General Public License v3 or later",
            "License-File" to "META-INF/LICENSE.txt",
            "WorldGuard-Version" to project.version,
        )
    }
}

val sourceDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the corresponding source and build scripts for redistribution."
    archiveClassifier.set("source-distribution")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(layout.projectDirectory) {
        include(
            "LICENSE.txt",
            "README.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/**",
            "src/main/**",
        )
    }
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"), sourceDistribution)
}
