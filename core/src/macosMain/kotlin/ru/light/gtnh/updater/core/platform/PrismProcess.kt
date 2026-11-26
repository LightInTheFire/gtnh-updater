package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system
import ru.light.gtnh.updater.core.model.PrismProfile

private const val PRISM_BUNDLE_EXECUTABLE = "Prism Launcher.app/Contents/MacOS/prismlauncher"

@OptIn(ExperimentalForeignApi::class)
internal actual fun startPrismProcess(profile: PrismProfile, arguments: List<String>) {
    val executables = listOfNotNull(
        "prismlauncher",
        "/Applications/$PRISM_BUNDLE_EXECUTABLE",
        readEnvironmentVariable("HOME")?.let { "$it/Applications/$PRISM_BUNDLE_EXECUTABLE" },
    )
    check(executables.any { system(posixLaunchScript(it, arguments)) == 0 }) { "Could not start Prism Launcher." }
}
