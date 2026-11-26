package ru.light.gtnh.updater.core.platform

import okio.FileSystem
import okio.Path

private const val LOCK_FILE = "updater.lock"

fun <T> withUpdaterLock(action: () -> T): T {
    val home = checkNotNull(appHomeDirectory()) { "The updater home directory is unavailable." }
    FileSystem.SYSTEM.createDirectories(home)
    return withFileLock(home / LOCK_FILE, action)
}

internal expect fun <T> withFileLock(path: Path, action: () -> T): T
