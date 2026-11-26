package ru.light.gtnh.updater.core.config

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path

internal const val MIGRATION_MARKER = ".gtnh-player-data-migrated"
private val worldMarkers = listOf("level.dat", "level.dat_old")
private val minecraftSettings = listOf("localconfig.cfg", "options.txt", "optionsnf.txt")
private val playerPaths = listOf(
    "saves", "journeymap", "TCNodeTracker", "visualprospecting", "schematics",
    "screenshots", "resourcepacks", "shaderpacks", "ESM", "BotaniaVars.dat",
    "servers.dat",
) + minecraftSettings

data class MigrationResult(val configFiles: Int, val playerEntries: Int)

data class MigrationProgress(
    val completedEntries: Int,
    val totalEntries: Int,
    val currentEntry: String?,
    val copiedFiles: Long = 0,
)

private const val PROGRESS_FILE_INTERVAL = 128

internal fun availablePlayerData(fs: FileSystem, source: Path): List<String> =
    playerPaths.filter { fs.metadataOrNull(source / it) != null }

internal fun playerFolders(fs: FileSystem, root: Path, entries: List<String>): List<String> =
    entries.flatMap { name ->
        val folder = root / name
        if (fs.metadataOrNull(folder)?.isDirectory != true || fs.metadata(folder).symlinkTarget != null) {
            emptyList()
        } else {
            listOf(name) + fs.list(folder).filter {
                fs.metadataOrNull(it)?.let { metadata -> metadata.isDirectory && metadata.symlinkTarget == null } == true
            }.map { "$name/${it.name}" }
        }
    }

internal class PlayerDataTransfer(
    private val fs: FileSystem,
    private val source: Path,
    private val target: Path,
    playerEntries: List<String>,
    private val mods: Set<String> = emptySet(),
    private val ignoredFolders: Set<String> = emptySet(),
    includeMinecraftSettings: Boolean = true,
) {
    private val entries = playerEntries.filterNot {
        it in ignoredFolders || (!includeMinecraftSettings && it in minecraftSettings)
    } + mods.sorted()
    val entryCount: Int get() = entries.size

    suspend fun prepare(transaction: MigrationTransaction, onProgress: (MigrationProgress) -> Unit = {}) {
        var copiedFiles = 0L
        for ((index, name) in entries.withIndex()) {
            onProgress(MigrationProgress(index, entryCount, name, copiedFiles))
            val countCopy = {
                copiedFiles++
                if (copiedFiles % PROGRESS_FILE_INTERVAL == 0L) {
                    onProgress(MigrationProgress(index, entryCount, name, copiedFiles))
                }
            }
            val old = source / name
            val current = target / name
            if (name == "saves" && fs.metadataOrNull(current)?.isDirectory == true) {
                checkWorldCollisions(old, current)
            }
            val staged = transaction.add(name)
            if (name in mods) {
                check(fs.metadataOrNull(current) == null) { "A target mod already exists: $name." }
            } else if (fs.metadataOrNull(current) != null) {
                copyTree(current, staged, emptySet(), countCopy)
            }
            val ignoredChildren = ignoredFolders.filter { it.startsWith("$name/") }
                .map { it.substringAfter('/') }.toSet()
            copyTree(old, staged, ignoredChildren, countCopy)
            onProgress(MigrationProgress(index + 1, entryCount, name, copiedFiles))
        }
    }

    private fun checkWorldCollisions(previousSaves: Path, candidateSaves: Path) {
        val existing = fs.list(candidateSaves).groupBy { it.name.lowercase() }
        for (entry in fs.list(previousSaves)) {
            if ("saves/${entry.name}" in ignoredFolders) continue
            val collisions = existing[entry.name.lowercase()].orEmpty()
            check(collisions.none { looksLikeWorld(entry) || looksLikeWorld(it) }) {
                "A world with the same folder name is already in the test instance: ${entry.name}."
            }
        }
    }

    private fun looksLikeWorld(path: Path): Boolean {
        val metadata = fs.metadata(path)
        return metadata.isDirectory && metadata.symlinkTarget == null &&
            worldMarkers.any { fs.metadataOrNull(path / it) != null }
    }


    private suspend fun copyTree(
        source: Path,
        target: Path,
        ignoredChildren: Set<String>,
        onCopied: () -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val metadata = fs.metadata(source)
        check(metadata.symlinkTarget == null) { "Linked player data is unsupported: ${source.name}" }
        val previous = fs.metadataOrNull(target)
        if (metadata.isDirectory) {
            check(previous == null || previous.isDirectory) { "Player data has a file/folder conflict: ${source.name}" }
            fs.createDirectory(target)
            for (child in fs.list(source)) {
                if (child.name !in ignoredChildren) copyTree(child, target / child.name, emptySet(), onCopied)
            }
        } else {
            check(metadata.isRegularFile && (previous == null || previous.isRegularFile)) {
                "Unsupported player data: ${source.name}"
            }
            fs.copy(source, target)
            onCopied()
        }
    }
}
