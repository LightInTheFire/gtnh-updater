package ru.light.gtnh.updater.core.diagnostics

import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import ru.light.gtnh.updater.core.platform.appHomeDirectory

private const val LOG_FILENAME = "updater.log"
private const val PREVIOUS_LOG_FILENAME = "updater.previous.log"
private const val MAX_LOG_BYTES = 1024L * 1024
private val versionFormat = Regex("[0-9][0-9A-Za-z.+-]{0,63}")

internal class DiagnosticLog(
    version: String?,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val home: Path? = appHomeDirectory(),
) {
    private val session = Uuid.random()
    private val path = home?.div(LOG_FILENAME)?.takeIf { version != null }

    init {
        runCatching {
            if (path != null && (fs.metadataOrNull(path)?.size ?: 0) >= MAX_LOG_BYTES) {
                val previous = checkNotNull(home) / PREVIOUS_LOG_FILENAME
                if (isRegularFile(path) && isRegularFile(previous)) fs.atomicMove(path, previous)
            }
        }
        if (version != null) {
            val safeVersion = version.takeIf(versionFormat::matches) ?: "unknown"
            record("application", "started version=$safeVersion")
        }
    }

    inline fun <T> track(operation: String, block: () -> T): T {
        record(operation, "started")
        try {
            return block().also { record(operation, "completed") }
        } catch (error: CancellationException) {
            record(operation, "cancelled")
            throw error
        } catch (error: Exception) {
            val cause = error.cause?.let { " cause=${it::class.simpleName}" }.orEmpty()
            // Exception messages can include user paths, URLs, or credentials.
            record(operation, "failed type=${error::class.simpleName}$cause")
            throw error
        }
    }

    private fun record(operation: String, outcome: String) {
        val file = path ?: return
        runCatching {
            fs.createDirectories(checkNotNull(home))
            if (!isRegularFile(file)) return
            if ((fs.metadataOrNull(file)?.size ?: 0) >= MAX_LOG_BYTES) return
            fs.appendingSink(file).buffer().use { sink ->
                sink.writeUtf8("${Clock.System.now()} session=$session operation=$operation $outcome\n")
            }
        }
    }

    private fun isRegularFile(file: Path): Boolean =
        fs.metadataOrNull(file)?.let { it.isRegularFile && it.symlinkTarget == null } ?: true
}
