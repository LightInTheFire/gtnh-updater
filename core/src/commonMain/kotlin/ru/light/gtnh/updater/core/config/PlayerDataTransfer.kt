package ru.light.gtnh.updater.core.config

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path

internal const val MIGRATION_MARKER = ".gtnh-player-data-migrated"
private val worldMarkers = listOf("level.dat", "level.dat_old")
private val playerPaths = listOf(
    "saves", "journeymap", "TCNodeTracker", "visualprospecting", "schematics",
    "screenshots", "resourcepacks", "shaderpacks", "ESM", "BotaniaVars.dat",
    "localconfig.cfg", "options.txt", "optionsnf.txt", "servers.dat",
)

data class MigrationResult(val configFiles: Int, val playerEntries: Int)

internal fun availablePlayerData(fs: FileSystem, source: Path): List<String> =
    playerPaths.filter { fs.metadataOrNull(source / it) != null }

internal class PlayerDataTransfer(
    private val fs: FileSystem,
    private val source: Path,
    private val target: Path,
    playerEntries: List<String>,
    private val mods: Set<String> = emptySet(),
) {
    private val entries = playerEntries + mods.sorted()
    suspend fun prepare(transaction: MigrationTransaction) {
        for (name in entries) {
            val old = source / name
            val current = target / name
            if (name == "saves" && fs.metadataOrNull(current)?.isDirectory == true) {
                checkWorldCollisions(old, current)
            }
            val staged = transaction.add(name)
            if (name in mods) {
                check(fs.metadataOrNull(current) == null) { "A target mod already exists: $name." }
            } else if (fs.metadataOrNull(current) != null) {
                copyTree(current, staged)
            }
            copyTree(old, staged)
        }
    }

    private fun checkWorldCollisions(previousSaves: Path, candidateSaves: Path) {
        val existing = fs.list(candidateSaves).groupBy { it.name.lowercase() }
        for (entry in fs.list(previousSaves)) {
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


    private suspend fun copyTree(source: Path, target: Path) {
        currentCoroutineContext().ensureActive()
        val metadata = fs.metadata(source)
        check(metadata.symlinkTarget == null) { "Linked player data is unsupported: ${source.name}" }
        val previous = fs.metadataOrNull(target)
        if (metadata.isDirectory) {
            check(previous == null || previous.isDirectory) { "Player data has a file/folder conflict: ${source.name}" }
            fs.createDirectory(target)
            for (child in fs.list(source)) copyTree(child, target / child.name)
        } else {
            check(metadata.isRegularFile && (previous == null || previous.isRegularFile)) {
                "Unsupported player data: ${source.name}"
            }
            fs.copy(source, target)
        }
    }
}
