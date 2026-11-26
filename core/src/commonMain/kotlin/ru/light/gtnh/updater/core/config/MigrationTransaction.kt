package ru.light.gtnh.updater.core.config

import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

internal const val MOD_TRANSFER_MARKER = ".gtnh-extra-mods-transferred"
internal const val MIGRATION_WORK = ".gtnh-migration-pending"
private const val PLAN = "plan.json"
private const val STAGED = "staged"
private const val BACKUPS = "backups"
private const val COMMITTED = "committed"

/** Stages replacements beside the destination, then publishes or restores them with atomic moves. */
internal class MigrationTransaction(private val fs: FileSystem, private val root: Path) {
    private val work = root / MIGRATION_WORK
    private val entries = mutableListOf<Entry>()

    fun begin() {
        recover()
        fs.createDirectory(work, mustCreate = true)
        fs.createDirectory(work / STAGED)
        fs.createDirectory(work / BACKUPS)
    }

    fun add(relative: String, replacement: Boolean = true): Path {
        val path = destination(relative)
        require(entries.none { it.relative == relative }) { "Duplicate migration path: $relative." }
        val index = entries.size
        entries += Entry(relative, fs.metadataOrNull(path) != null, replacement)
        return staged(index)
    }

    fun commit(marker: String?, value: String = "", beforeCompletion: () -> Unit = {}) {
        require(validMarker(marker)) { "Invalid migration marker." }
        val plan = JsonObject(mapOf(
            "marker" to (marker?.let(::JsonPrimitive) ?: JsonNull),
            "entries" to JsonArray(entries.map {
                JsonObject(mapOf(
                    "path" to JsonPrimitive(it.relative),
                    "original" to JsonPrimitive(it.hadOriginal),
                    "replacement" to JsonPrimitive(it.hasReplacement),
                ))
            }),
        ))
        writeAtomic(work / PLAN, plan.toString())
        for ((index, entry) in entries.withIndex()) {
            val path = destination(entry.relative)
            check((fs.metadataOrNull(path) != null) == entry.hadOriginal) {
                "Migration destination changed: ${entry.relative}."
            }
            if (entry.hadOriginal) fs.atomicMove(path, backup(index))
            if (entry.hasReplacement) {
                fs.createDirectories(checkNotNull(path.parent))
                fs.atomicMove(staged(index), path)
            }
        }
        beforeCompletion()
        val completed = if (marker != null) root / marker else work / COMMITTED
        writeAtomic(completed, value)
        runCatching { discardWork() }
    }

    fun recover() {
        val metadata = fs.metadataOrNull(work) ?: return
        check(metadata.isDirectory && metadata.symlinkTarget == null) { "Invalid migration work directory." }
        val manifest = work / PLAN
        if (!fs.exists(manifest)) {
            check(fs.list(work).all { it.name == STAGED || it.name == BACKUPS || it.name.startsWith(".plan.json-") }) {
                "Unrecognized files in the migration work directory."
            }
            val backups = work / BACKUPS
            check(!fs.exists(backups) || fs.list(backups).isEmpty()) { "Migration backups are missing their journal." }
            discardWork()
            return
        }
        val (marker, saved) = loadPlan(manifest)
        val completed = if (marker != null) root / marker else work / COMMITTED
        if (!fs.exists(completed)) {
            for (index in saved.indices.reversed()) {
                val entry = saved[index]
                val path = destination(entry.relative)
                val previous = backup(index)
                val incoming = staged(index)
                if (entry.hasReplacement && !fs.exists(incoming) && fs.exists(path) &&
                    (fs.exists(previous) || !entry.hadOriginal)
                ) {
                    fs.atomicMove(path, incoming)
                }
                if (fs.exists(previous)) {
                    check(!fs.exists(path)) { "Could not safely restore ${entry.relative}." }
                    fs.atomicMove(previous, path)
                } else if (entry.hadOriginal) {
                    check(fs.exists(path)) { "The backup for ${entry.relative} is missing." }
                }
            }
        }
        discardWork()
    }

    private fun loadPlan(path: Path): Pair<String?, List<Entry>> {
        check(fs.metadata(path).symlinkTarget == null) { "Invalid migration journal." }
        for (name in listOf(STAGED, BACKUPS)) {
            val metadata = fs.metadata(work / name)
            check(metadata.isDirectory && metadata.symlinkTarget == null) { "Invalid migration journal directory." }
        }
        val json = fs.read(path) { Json.parseToJsonElement(readUtf8()) } as JsonObject
        val rawMarker = json.getValue("marker")
        val marker = if (rawMarker == JsonNull) null else (rawMarker as JsonPrimitive).content
        check(validMarker(marker)) { "Invalid migration marker." }
        val saved = (json.getValue("entries") as JsonArray).map {
            val entry = it as JsonObject
            Entry(
                (entry.getValue("path") as JsonPrimitive).content,
                (entry.getValue("original") as JsonPrimitive).boolean,
                (entry.getValue("replacement") as JsonPrimitive).boolean,
            )
        }
        check(saved.map(Entry::relative).distinct().size == saved.size) { "Invalid migration journal paths." }
        saved.forEach { destination(it.relative) }
        return marker to saved
    }

    private fun destination(relative: String): Path {
        val parsed = relative.toPath()
        val parts = parsed.segments
        check(!parsed.isAbsolute && parts.isNotEmpty() && parts.joinToString("/") == relative &&
            parts.all { it.isNotEmpty() && it != "." && it != ".." } &&
            parts.first() != MIGRATION_WORK && parts.first() != MIGRATION_MARKER && parts.first() != MOD_TRANSFER_MARKER
        ) { "Invalid migration path." }
        var path = root
        for (segment in parts) {
            path /= segment
            check(fs.metadataOrNull(path)?.symlinkTarget == null) { "Linked migration path: $relative." }
        }
        return path
    }

    private fun staged(index: Int): Path = work / STAGED / index.toString()

    private fun backup(index: Int): Path = work / BACKUPS / index.toString()

    private fun validMarker(marker: String?): Boolean =
        marker == null || marker == MIGRATION_MARKER || marker == MOD_TRANSFER_MARKER

    private fun writeAtomic(path: Path, text: String) {
        val pending = checkNotNull(path.parent) / ".${path.name}-${Uuid.random()}.tmp"
        try {
            fs.write(pending, mustCreate = true) { writeUtf8(text) }
            fs.atomicMove(pending, path)
        } finally {
            fs.delete(pending)
        }
    }

    private fun discardWork() {
        val retired = root / ".gtnh-migration-cleanup-${Uuid.random()}"
        fs.atomicMove(work, retired)
        runCatching { fs.deleteRecursively(retired) }
    }

    private data class Entry(val relative: String, val hadOriginal: Boolean, val hasReplacement: Boolean)
}
