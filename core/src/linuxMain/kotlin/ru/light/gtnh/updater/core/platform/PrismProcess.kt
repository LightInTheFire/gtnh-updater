package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system
import ru.light.gtnh.updater.core.model.PrismProfile

private const val FLATPAK_PRISM = "org.prismlauncher.PrismLauncher"

@OptIn(ExperimentalForeignApi::class)
internal actual fun startPrismProcess(profile: PrismProfile, arguments: List<String>) {
    val launchArguments = if (profile.flatpak) listOf("run", FLATPAK_PRISM) + arguments else arguments
    val executables = if (profile.flatpak) {
        listOf("flatpak")
    } else {
        listOf((profile.root / "bin/prismlauncher").toString(), "prismlauncher")
    }
    check(executables.any { system(posixLaunchScript(it, launchArguments)) == 0 }) { "Could not start Prism Launcher." }
}
