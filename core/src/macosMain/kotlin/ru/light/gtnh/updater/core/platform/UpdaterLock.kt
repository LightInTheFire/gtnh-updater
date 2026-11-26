package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.Path
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withFileLock(path: Path, action: () -> T): T {
    val fd = open(path.toString(), O_CREAT or O_RDWR or O_CLOEXEC, S_IRUSR or S_IWUSR)
    check(fd >= 0) { "Could not open the updater lock." }
    try {
        val locked = memScoped {
            val lock = alloc<flock>()
            lock.l_type = F_WRLCK.convert()
            lock.l_whence = SEEK_SET.convert()
            lock.l_start = 0
            lock.l_len = 0
            lock.l_pid = 0
            fcntl(fd, F_SETLK, lock.ptr) == 0
        }
        if (!locked) {
            val code = errno
            check(code != EACCES && code != EAGAIN) { "Another GTNH Updater process is already running." }
            error("Could not acquire the updater lock.")
        }
        return action()
    } finally {
        close(fd)
    }
}
