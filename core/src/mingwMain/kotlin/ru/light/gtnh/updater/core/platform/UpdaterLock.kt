package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.ERROR_SHARING_VIOLATION
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_ALWAYS

@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withFileLock(path: Path, action: () -> T): T {
    val handle = CreateFileW(
        path.toString(), GENERIC_WRITE.toUInt(), 0u, null, OPEN_ALWAYS.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null,
    )
    if (handle == INVALID_HANDLE_VALUE) {
        check(GetLastError() != ERROR_SHARING_VIOLATION.toUInt()) { "Another GTNH Updater process is already running." }
        error("Could not acquire the updater lock.")
    }
    try {
        return action()
    } finally {
        CloseHandle(handle)
    }
}
