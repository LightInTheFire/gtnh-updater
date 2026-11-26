package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import okio.Path
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod

@OptIn(ExperimentalForeignApi::class)
internal actual fun protectSettingsFile(path: Path) {
    check(chmod(path.toString(), (S_IRUSR or S_IWUSR).convert()) == 0) {
        "Could not restrict access to the settings file."
    }
}
