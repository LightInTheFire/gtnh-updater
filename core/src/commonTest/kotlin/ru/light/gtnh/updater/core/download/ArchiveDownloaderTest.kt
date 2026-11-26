package ru.light.gtnh.updater.core.download

import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.openZip
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.TransferProgress

class ArchiveDownloaderTest {
    private val fileSystem = FileSystem.SYSTEM
    private val fixture = checkNotNull(
        (
            "UEsDBBQAAAAAAAAAIQDuQOUFBwAAAAcAAAAIAAAAcGFjay50eHRmaXh0dXJlUEsBAhQDFAAAAAAAAAAhAO5A5QUHAAAABw" +
                "AAAAgAAAAAAAAAAAAAAIABAAAAAHBhY2sudHh0UEsFBgAAAAABAAEANgAAAC0AAAAAAA=="
            ).decodeBase64(),
    ).toByteArray()

    @Test
    fun publishesCompletedFileAndReportsProgress() = withDestination { target ->
        val reports = mutableListOf<TransferProgress>()
        val total = fixture.size.toLong()
        val saved = ArchiveDownloader().saveArchive(target, ByteReadChannel(fixture), total) {
            assertFalse(fileSystem.exists(target))
            reports += it
        }
        assertEquals(target, saved)
        assertEquals(TransferProgress(total = total), reports.first())
        assertEquals(TransferProgress(total, total), reports.last())
        assertContentEquals(fixture, fileSystem.read(target) { readByteArray() })
        assertEquals(listOf(target), fileSystem.list(target.parent!!))
    }

    @Test
    fun truncatedTransferPreservesPreviousArchive() = withDestination { target ->
        val previous = "previous"
        fileSystem.write(target) { writeUtf8(previous) }
        assertFailsWith<IllegalStateException> {
            ArchiveDownloader().saveArchive(target, ByteReadChannel(fixture), fixture.size + 1L) { }
        }
        assertEquals(previous, fileSystem.read(target) { readUtf8() })
        assertEquals(listOf(target), fileSystem.list(target.parent!!))
    }

    @Test
    fun cancellationRemovesPartialFile() = withDestination { target ->
        assertFailsWith<CancellationException> {
            ArchiveDownloader().saveArchive(target, ByteReadChannel(fixture), null) {
                if (it.received > 0L) throw CancellationException()
            }
        }
        assertTrue(fileSystem.list(target.parent!!).isEmpty())
    }

    @Test
    fun rejectsNonZipResponses() = withDestination { target ->
        assertFailsWith<IllegalStateException> {
            ArchiveDownloader().saveArchive(target, ByteReadChannel("<html>"), null) { }
        }
        assertFalse(fileSystem.exists(target))
        assertTrue(fileSystem.list(target.parent!!).isEmpty())
    }

    @Test
    fun rejectsTruncatedZipWithoutContentLength() = withDestination { target ->
        val truncated = fixture.copyOf(fixture.size / 2)
        assertFailsWith<IOException> {
            ArchiveDownloader().saveArchive(target, ByteReadChannel(truncated), null) { }
        }
        assertFalse(fileSystem.exists(target))
        assertTrue(fileSystem.list(target.parent!!).isEmpty())
    }

    @Test
    fun reusesCompletedArchiveAcrossSessionsWithoutStartingATransfer() = withDestination { target ->
        val root = checkNotNull(target.parent)
        val filename = "GT_New_Horizons_2.9.2_Java_8.zip"
        val url = "https://example.invalid/stable/$filename"
        val cache = ArchiveCache(fileSystem, root, root)
        val downloaded = cache.newDestination(filename)
        ArchiveDownloader(fileSystem, cache).saveArchive(downloaded, ByteReadChannel(fixture), null) {}
        cache.remember(url, downloaded)

        val next = ArchiveCache(fileSystem, root, root)
        val result = ArchiveDownloader(fileSystem, next).download(ClientArchive("Java 8", url)) {
            error("A cache hit must not start a transfer")
        }
        assertEquals(downloaded, result.path)
        assertTrue(result.fromCache)
        assertNull(next.find("https://example.invalid/beta/$filename", filename))

        val entryDataOffset = 38 // Local ZIP header and the "pack.txt" filename.
        val corrupted = fixture.copyOf().also { it[entryDataOffset] = '!'.code.toByte() }
        fileSystem.write(downloaded) { write(corrupted) }
        fileSystem.openZip(downloaded).close()
        assertNull(next.find(url, filename))
        fileSystem.delete(downloaded)
        assertNull(next.find(url, filename))
    }

    private fun withDestination(test: suspend (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-download-test-${Uuid.random()}"
        try {
            fileSystem.createDirectories(directory)
            runBlocking { test(directory / "pack.zip") }
        } finally {
            fileSystem.deleteRecursively(directory)
        }
    }
}
