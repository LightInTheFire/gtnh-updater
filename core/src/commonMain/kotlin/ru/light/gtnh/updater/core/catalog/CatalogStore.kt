package ru.light.gtnh.updater.core.catalog

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.platform.appHomeDirectory
import ru.light.gtnh.updater.core.platform.previousCatalogDirectory

internal class CatalogStore(
    private val directory: Path?,
    private val legacyDirectory: Path? = null,
) {
    constructor() : this(appHomeDirectory(), previousCatalogDirectory())

    fun load(channel: PackChannel): ChannelCatalog =
        readCache(directory, channel)?.catalog
            ?: readCache(legacyDirectory, channel)?.catalog
            ?: ChannelCatalog(emptyList(), status = "No local catalog")

    fun migrateLegacy() {
        val destination = directory ?: return
        val source = legacyDirectory?.takeIf { it != destination } ?: return
        val fs = FileSystem.SYSTEM
        for (channel in PackChannel.entries) {
            runCatching {
                val filename = catalogFilename(channel)
                if (fs.exists(destination / filename)) return@runCatching
                val previous = readCache(source, channel) ?: return@runCatching
                save(channel, previous.body)
                fs.delete(source / filename)
            }
        }
    }

    private fun readCache(directory: Path?, channel: PackChannel): CachedCatalog? {
        val path = directory?.div(catalogFilename(channel)) ?: return null
        return runCatching {
            val body = FileSystem.SYSTEM.read(path) { readUtf8() }
            CachedCatalog(body, parseCatalog(channel, body, "Local catalog"))
        }.getOrNull()
    }

    suspend fun refresh(channel: PackChannel, githubToken: String? = null): ChannelCatalog {
        val body = fetchCatalogIndex(channel, githubToken)
        val catalog = parseCatalog(channel, body, "Catalog refreshed")
        currentCoroutineContext().ensureActive()
        try {
            save(channel, body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return catalog.copy(status = "Catalog refreshed; local cache unavailable")
        }
        return catalog
    }

    private fun save(channel: PackChannel, body: String) {
        val dir = checkNotNull(directory)
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir)
        val target = dir / catalogFilename(channel)
        val temporary = dir / "${channel.label}-${Uuid.random()}.tmp"
        try {
            fs.write(temporary, mustCreate = true) { writeUtf8(body) }
            fs.atomicMove(temporary, target)
        } finally {
            fs.delete(temporary)
        }
    }
}

private data class CachedCatalog(val body: String, val catalog: ChannelCatalog)

private fun catalogFilename(channel: PackChannel): String = "${channel.label}.json"
