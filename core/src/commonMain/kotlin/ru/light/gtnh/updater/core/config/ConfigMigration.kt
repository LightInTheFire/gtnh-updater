package ru.light.gtnh.updater.core.config

import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.instance.instanceGameDirectory
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.platform.appHomeDirectory

private val configDirectories = listOf("config", "configs")
private const val MAX_CONFIG_BYTES = 512L * 1024
private const val HISTORY_FILENAME = "config-choices.json"

internal class ConfigMigration(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val home: Path? = appHomeDirectory(),
) {
    private val lock = Mutex()

    fun recover(prepared: PreparedInstance) = MigrationTransaction(fs, gameDirectory(prepared.candidate)).recover()

    fun hasTransferredMods(prepared: PreparedInstance): Boolean =
        fs.exists(gameDirectory(prepared.candidate) / MOD_TRANSFER_MARKER)

    suspend fun reviewExtraMods(prepared: PreparedInstance): ExtraMods = lock.withLock {
        val source = gameDirectory(prepared.source)
        val candidate = gameDirectory(prepared.candidate)
        require(source != candidate) { "Choose two different instances." }
        MigrationTransaction(fs, candidate).recover()
        ModInventory(fs).review(prepared.source, source, candidate)
    }

    suspend fun transferExtraMods(prepared: PreparedInstance, review: ExtraMods, selected: Set<String>): Int = lock.withLock {
        val source = gameDirectory(prepared.source)
        val candidate = gameDirectory(prepared.candidate)
        require(source != candidate) { "Choose two different instances." }
        val transaction = MigrationTransaction(fs, candidate)
        transaction.recover()
        val marker = candidate / MOD_TRANSFER_MARKER
        check(!fs.exists(marker) && !fs.exists(candidate / MIGRATION_MARKER)) { "The extra mods step is already complete." }
        check(review.paths.containsAll(selected)) { "Only mods selected in this review can be transferred." }
        if (selected.isNotEmpty()) {
            val inventory = ModInventory(fs)
            check(inventory.review(prepared.source, source, candidate).paths.containsAll(selected)) {
                "The mod list changed since review. Reopen the extra mods screen."
            }
            inventory.ensureDistinctSelection(source, selected)
            selected.forEach {
                checkSafePath(source, source / it)
                checkSafePath(candidate, candidate / it)
            }
        }
        val transfer = PlayerDataTransfer(fs, source, candidate, emptyList(), selected)
        transaction.begin()
        try {
            transfer.prepare(transaction)
            currentCoroutineContext().ensureActive()
            transaction.commit(MOD_TRANSFER_MARKER, "${selected.size}\n")
        } catch (error: Exception) {
            runCatching { transaction.recover() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        selected.size
    }

    suspend fun review(prepared: PreparedInstance): ConfigReview = lock.withLock {
        val source = gameDirectory(prepared.source)
        val candidate = gameDirectory(prepared.candidate)
        require(source != candidate) { "Choose two different instances." }
        MigrationTransaction(fs, candidate).recover()
        if (fs.exists(candidate / MIGRATION_MARKER)) {
            return@withLock ConfigReview(candidate, emptyList(), emptyList(), migrationComplete = true)
        }
        val history = loadHistory()
        val skipped = linkedSetOf<String>()
        val previous = collect(source, skipped)
        val incoming = collect(candidate, skipped)
        val files = mutableListOf<ConfigFile>()
        val allPaths = (previous + incoming).asSequence()
            .filter { path -> prepared.shippedConfigs?.let { path in it } != false }
            .filterNot { path -> path.split('/').any { it.equals("generated", ignoreCase = true) } }
            .sorted()
            .toList()
        val conflicts = allPaths.flatMap { path ->
            generateSequence(path.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
                .takeWhile { it.isNotEmpty() }
                .filter { it in previous || it in incoming }
                .toList()
        }.toSet()
        skipped += conflicts
        for (path in allPaths) {
            currentCoroutineContext().ensureActive()
            if (skipped.any { path == it || path.startsWith("$it/") }) continue
            val readable = try {
                val before = if (path in previous) readText(source / path) else null
                val after = if (path in incoming) readText(candidate / path) else null
                val difference = if (before == after) null else diffConfig(path, before, after, history)
                difference?.takeIf { it.edits.isNotEmpty() }?.let(files::add)
                before == after || difference != null
            } catch (_: IllegalStateException) {
                false
            } catch (_: okio.IOException) {
                false
            } catch (_: kotlin.text.CharacterCodingException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
            if (!readable) skipped += path
        }
        ConfigReview(candidate, files, skipped.sorted(), availablePlayerData(fs, source))
    }

    suspend fun apply(prepared: PreparedInstance, review: ConfigReview, choices: Map<String, ConfigChoice>): Int =
        apply(prepared, review, choices, migratePlayerData = false).configFiles

    suspend fun apply(
        prepared: PreparedInstance,
        review: ConfigReview,
        choices: Map<String, ConfigChoice>,
        migratePlayerData: Boolean,
    ): MigrationResult =
        lock.withLock {
            val candidate = gameDirectory(prepared.candidate)
            val source = gameDirectory(prepared.source)
            require(candidate != source) { "Choose two different instances." }
            require(candidate == review.target) { "This review belongs to a different candidate." }
            val transaction = MigrationTransaction(fs, candidate)
            transaction.recover()
            val marker = candidate / MIGRATION_MARKER
            if (migratePlayerData) check(!fs.exists(marker)) { "Migration is already complete for this instance." }
            val history = loadHistory().toMutableMap()
            val writes = mutableListOf<ConfigWrite>()
            for (file in review.files) {
                currentCoroutineContext().ensureActive()
                val path = candidate / file.path
                checkSafePath(candidate, path)
                val existing = if (fs.exists(path)) readText(path) else null
                check(existing == file.after) { "Config changed since review: ${file.path}. Reopen the review." }
                val merged = file.merge(choices)
                if (merged != existing) writes += ConfigWrite(path, existing, merged)
                for (edit in file.edits) {
                    choices[edit.change.id]?.let { history[edit.change.id] = it }
                }
            }
            val changedConfigs = writes.size
            val historyWrite = if (choices.isNotEmpty()) {
                val path = checkNotNull(home) { "The updater home directory is unavailable." } / HISTORY_FILENAME
                val json = JsonObject(history.mapValues { JsonPrimitive(it.value.name) }).toString()
                ConfigWrite(path, if (fs.exists(path)) fs.read(path) { readUtf8() } else null, json)
            } else null
            val playerTransfer = if (migratePlayerData) PlayerDataTransfer(fs, source, candidate, review.playerData) else null
            var historyWritten = false
            transaction.begin()
            try {
                playerTransfer?.prepare(transaction)
                for (write in writes) {
                    currentCoroutineContext().ensureActive()
                    val relative = write.path.relativeTo(candidate).segments.joinToString("/")
                    val staged = transaction.add(relative, replacement = write.after != null)
                    write.after?.let { fs.write(staged, mustCreate = true) { writeUtf8(it) } }
                }
                currentCoroutineContext().ensureActive()
                transaction.commit(MIGRATION_MARKER.takeIf { migratePlayerData }, "Player data and configs migrated.\n") {
                    historyWrite?.let {
                        writeText(it.path, it.after)
                        historyWritten = true
                    }
                }
            } catch (error: Exception) {
                runCatching { transaction.recover() }.exceptionOrNull()?.let(error::addSuppressed)
                if (historyWritten && historyWrite != null) {
                    runCatching { writeText(historyWrite.path, historyWrite.before) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
            MigrationResult(changedConfigs, if (migratePlayerData) review.playerData.size else 0)
        }

    private fun gameDirectory(path: Path): Path =
        checkNotNull(instanceGameDirectory(fs, fs.canonicalize(path))) { "The instance is unavailable: $path" }
            .let { check(fs.metadata(it).symlinkTarget == null) { "Linked game directories are unsupported." }
                it }

    private fun collect(root: Path, skipped: MutableSet<String>): Set<String> {
        val paths = linkedSetOf<String>()
        fun visit(path: Path) {
            val metadata = fs.metadataOrNull(path) ?: return
            val relative = path.relativeTo(root).segments.joinToString("/")
            when {
                metadata.symlinkTarget != null -> skipped += relative
                metadata.isDirectory -> fs.list(path).forEach(::visit)
                metadata.isRegularFile -> paths += relative
                else -> skipped += relative
            }
        }
        configDirectories.forEach { visit(root / it) }
        return paths
    }

    private fun checkSafePath(root: Path, path: Path) {
        var current = root
        for (segment in path.relativeTo(root).segments) {
            current /= segment
            check(fs.metadataOrNull(current)?.symlinkTarget == null) { "Linked config paths are unsupported: $path" }
        }
    }

    private fun readText(path: Path): String {
        val metadata = fs.metadata(path)
        check(metadata.isRegularFile && metadata.symlinkTarget == null && (metadata.size ?: Long.MAX_VALUE) <= MAX_CONFIG_BYTES)
        val bytes = fs.read(path) {
            check(!request(MAX_CONFIG_BYTES + 1L))
            readByteArray()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true).also { text ->
            check(text.none { it.isISOControl() && it !in "\r\n\t" })
        }
    }

    private fun loadHistory(): Map<String, ConfigChoice> {
        val path = home?.div(HISTORY_FILENAME) ?: return emptyMap()
        if (!fs.exists(path)) return emptyMap()
        val json = fs.read(path) { Json.parseToJsonElement(readUtf8()) } as JsonObject
        return json.mapNotNull { (id, value) ->
            ConfigChoice.entries.firstOrNull { it.name == (value as? JsonPrimitive)?.content }?.let { id to it }
        }.toMap()
    }

    private fun writeText(path: Path, text: String?) {
        if (text == null) {
            fs.delete(path)
            return
        }
        val parent = checkNotNull(path.parent)
        fs.createDirectories(parent)
        val temporary = parent / ".gtnh-config-${Uuid.random()}.tmp"
        try {
            fs.write(temporary, mustCreate = true) { writeUtf8(text) }
            fs.atomicMove(temporary, path)
        } finally {
            fs.delete(temporary)
        }
    }
}

private data class ConfigWrite(val path: Path, val before: String?, val after: String?)
