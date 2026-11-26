package ru.light.gtnh.updater.core.platform

import okio.Path
import okio.Path.Companion.toPath

private const val APP_DIRECTORY_NAME = "gtnh-updater"

internal fun environmentDirectory(name: String): Path? = readEnvironmentVariable(name)
    ?.takeIf { it.isNotBlank() }?.toPath()?.takeIf { it.isAbsolute }

internal fun appHomeDirectory(): Path? =
    (environmentDirectory("USERPROFILE") ?: environmentDirectory("HOME"))?.div(".$APP_DIRECTORY_NAME")

internal fun previousCatalogDirectory(): Path? {
    val base = environmentDirectory("XDG_CACHE_HOME")
        ?: environmentDirectory("LOCALAPPDATA")
        ?: environmentDirectory("HOME")?.div(".cache")
    return base?.div(APP_DIRECTORY_NAME)?.div("catalog")
}
