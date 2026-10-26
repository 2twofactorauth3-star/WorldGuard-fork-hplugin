import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.github.johnrengelman:shadow:8.1.1")
        classpath("org.ow2.asm:asm:9.7")
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
    compileOnly(libs.essentialsx) {
        isTransitive = false
    }
    compileOnly(libs.premiumVanishApi) {
        isTransitive = false
    }
    compileOnly(libs.cmiApi) {
        isTransitive = false
    }
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
    testRuntimeOnly("com.mojang:brigadier:1.2.9")
    testImplementation(libs.hamcrest.library)
    testImplementation(libs.paperApi) {
        isTransitive = false
    }
    testRuntimeOnly(libs.paperApi) {
        exclude("junit", "junit")
        exclude(group = "org.apache.maven")
        exclude(group = "org.apache.maven.resolver")
    }
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

tasks.named<JavaCompile>("compileJava") {
    doLast("addRegionRemovalCompatibilityBridge") {
        val ownerName = "com/sk89q/worldguard/protection/managers/RegionManager"
        val valueDescriptor = "(Ljava/lang/String;)Ljava/util/Set;"
        val voidDescriptor = "(Ljava/lang/String;)V"
        val classFile = destinationDirectory.file("$ownerName.class").get().asFile
        val reader = ClassReader(classFile.readBytes())
        val writer = ClassWriter(reader, 0)
        var valueMethodFound = false
        var voidMethodFound = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String,
                                     signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name == "removeRegion") {
                    if (descriptor == valueDescriptor) valueMethodFound = true
                    if (descriptor == voidDescriptor) voidMethodFound = true
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }

            override fun visitEnd() {
                check(valueMethodFound) { "RegionManager.removeRegion(String) returning Set is missing" }
                if (!voidMethodFound) {
                    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC,
                            "removeRegion", voidDescriptor, null, null).apply {
                        visitCode()
                        visitVarInsn(Opcodes.ALOAD, 0)
                        visitVarInsn(Opcodes.ALOAD, 1)
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, ownerName, "removeRegion", valueDescriptor, false)
                        visitInsn(Opcodes.POP)
                        visitInsn(Opcodes.RETURN)
                        visitMaxs(2, 2)
                        visitEnd()
                    }
                }
                super.visitEnd()
            }
        }, 0)
        classFile.writeBytes(writer.toByteArray())
    }
}

tasks.jar {
    enabled = false
}

val cleanPluginArtifacts by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("libs"))
}

tasks.named<ShadowJar>("shadowJar") {
    mustRunAfter(cleanPluginArtifacts)
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

tasks.register("pluginBuild") {
    group = "build"
    description = "Builds the single deployable WorldGuard Bukkit plugin JAR."
    dependsOn(cleanPluginArtifacts, tasks.named("shadowJar"))
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}
