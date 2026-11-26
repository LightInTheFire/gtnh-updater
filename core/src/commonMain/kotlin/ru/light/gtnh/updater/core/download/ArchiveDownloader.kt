package ru.light.gtnh.updater.core.download

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path
import okio.openZip
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.DownloadedArchive
import ru.light.gtnh.updater.core.model.TransferProgress
import ru.light.gtnh.updater.core.platform.appHomeDirectory

private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000L
private const val DOWNLOAD_IDLE_TIMEOUT_MS = 30_000L
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
private val ZIP_FILE_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
private val archiveFilename = Regex("""GT(?:NH-|_New_Horizons_)[A-Za-z0-9._+()-]+\.zip""")

internal class ArchiveDownloader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val cache: ArchiveCache = ArchiveCache(fileSystem, appHomeDirectory()),
) {
    suspend fun download(archive: ClientArchive, onProgress: (TransferProgress) -> Unit): DownloadedArchive {
        val url = Url(archive.url)
        require(url.protocol == URLProtocol.HTTPS) { "Archive downloads require HTTPS." }
        val filename = url.encodedPath.substringAfterLast('/').decodeURLPart()
        require(archiveFilename.matches(filename)) { "Unexpected GTNH archive filename." }
        cache.find(archive.url, filename)?.let {
            currentCoroutineContext().ensureActive()
            return DownloadedArchive(it, fromCache = true)
        }
        val destination = cache.newDestination(filename)

        val path = HttpClient {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = DOWNLOAD_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = DOWNLOAD_IDLE_TIMEOUT_MS
            }
        }.use { client ->
            client.prepareGet(archive.url) {
                header(HttpHeaders.UserAgent, "gtnh-updater")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                check(response.status == HttpStatusCode.OK) { "Unexpected HTTP status: ${response.status.value}." }
                saveArchive(destination, response.bodyAsChannel(), response.contentLength(), onProgress)
            }
        }
        try {
            cache.remember(archive.url, path)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A completed download remains usable when its cache record cannot be written.
        }
        return DownloadedArchive(path, fromCache = false)
    }

    internal suspend fun saveArchive(
        destination: Path,
        body: ByteReadChannel,
        total: Long?,
        onProgress: (TransferProgress) -> Unit,
    ): Path {
        val directory = requireNotNull(destination.parent)
        val temporary = directory / ".${destination.name}-${Uuid.random()}.part"
        val header = ByteArray(ZIP_FILE_HEADER.size)
        onProgress(TransferProgress(total = total))
        body.readFully(header)
        check(header.contentEquals(ZIP_FILE_HEADER)) { "The response is not a GTNH ZIP archive." }

        fileSystem.createDirectories(directory)
        try {
            fileSystem.write(temporary, mustCreate = true) {
                write(header)
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var received = header.size.toLong()
                onProgress(TransferProgress(received, total))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = body.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    write(buffer, 0, count)
                    received += count
                    onProgress(TransferProgress(received, total))
                }
                body.closedCause?.let { throw it }
                check(total == null || received == total) { "The downloaded size does not match Content-Length." }
            }
            fileSystem.openZip(temporary).close()
            currentCoroutineContext().ensureActive()
            fileSystem.atomicMove(temporary, destination)
            return destination
        } finally {
            fileSystem.delete(temporary)
        }
    }
}
