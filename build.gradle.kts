import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    kotlin("multiplatform") version "2.4.21"
}

group = "ru.light"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    val nativeTargets = listOf(linuxX64(), linuxArm64(), macosX64(), macosArm64(), mingwX64())

    for (target in nativeTargets) {
        target.binaries.executable(listOf(NativeBuildType.RELEASE)) {
            baseName = project.name
            entryPoint = "ru.light.gtnh.updater.main"

            val executable = this
            val executableName = if (target.name == "mingwX64") "${project.name}.exe" else project.name
            val targetName = target.name
            tasks.register<Sync>("packageRelease${targetName.replaceFirstChar { it.uppercaseChar() }}") {
                group = "distribution"
                description = "Stages the release executable for $targetName."
                from(executable.linkTaskProvider.map { executable.outputFile }) {
                    rename { executableName }
                    filePermissions { unix("755") }
                }
                into(layout.buildDirectory.dir("release/$targetName"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("com.github.ajalt.mordant:mordant:3.1.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
