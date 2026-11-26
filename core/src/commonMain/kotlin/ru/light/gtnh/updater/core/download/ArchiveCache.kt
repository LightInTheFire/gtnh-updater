package ru.light.gtnh.updater.core.download

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.openZip
import okio.use

private const val ARCHIVE_DIRECTORY_PREFIX = "gtnh-updater-"
private const val RECORD_LIMIT_BYTES = 160L
private const val HASH_BUFFER_BYTES = 128L * 1024
private val sha256 = Regex("[0-9a-f]{64}")

internal class ArchiveCache(
    private val fs: FileSystem,
    home: Path?,
    private val temporary: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
) {
    private val records = home?.div("downloads")

    fun newDestination(filename: String): Path =
        temporary / "$ARCHIVE_DIRECTORY_PREFIX${Uuid.random()}" / filename

    suspend fun find(url: String, filename: String): Path? {
        val record = record(url) ?: return null
        return try {
            val metadata = fs.metadataOrNull(record) ?: return null
            if (!metadata.isRegularFile || metadata.symlinkTarget != null ||
                (metadata.size ?: Long.MAX_VALUE) > RECORD_LIMIT_BYTES
            ) return null
            val (id, size, expectedHash) = fs.read(record) {
                val id = readUtf8Line()?.let { Uuid.parseOrNull(it) } ?: return@read null
                val size = readUtf8Line()?.toLongOrNull() ?: return@read null
                val hash = readUtf8Line()?.takeIf(sha256::matches) ?: return@read null
                Triple(id, size, hash)
            } ?: return null
            val directory = temporary / "$ARCHIVE_DIRECTORY_PREFIX$id"
            val folder = fs.metadataOrNull(directory) ?: return null
            if (!folder.isDirectory || folder.symlinkTarget != null) return null
            val path = directory / filename
            val archive = fs.metadataOrNull(path) ?: return null
            if (!archive.isRegularFile || archive.symlinkTarget != null || archive.size != size) return null
            if (digest(path) != expectedHash) return null
            fs.openZip(path).close()
            path
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    suspend fun remember(url: String, path: Path) {
        val record = record(url) ?: return
        val directory = checkNotNull(records)
        val id = checkNotNull(path.parent).name.removePrefix(ARCHIVE_DIRECTORY_PREFIX)
        val size = checkNotNull(fs.metadata(path).size)
        val hash = digest(path)
        val pending = directory / ".${Uuid.random()}.tmp"
        fs.createDirectories(directory)
        try {
            fs.write(pending, mustCreate = true) { writeUtf8("$id\n$size\n$hash\n") }
            fs.atomicMove(pending, record)
        } finally {
            fs.delete(pending)
        }
    }

    private suspend fun digest(path: Path): String = HashingSource.sha256(fs.source(path)).use { source ->
        val buffer = Buffer()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (source.read(buffer, HASH_BUFFER_BYTES) == -1L) break
            buffer.clear()
        }
        source.hash.hex()
    }

    private fun record(url: String): Path? = records?.div(url.encodeUtf8().sha256().hex())
}
