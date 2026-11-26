package ru.light.gtnh.updater.core.instance

import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.InstanceCopy
import ru.light.gtnh.updater.core.model.InstanceCopyKind

internal const val STAGING_PREFIX = ".gtnh-updater-"

internal class InstanceCopies(private val fs: FileSystem = FileSystem.SYSTEM) {
    fun list(roots: Set<Path>, protected: Map<Path, String>): List<InstanceCopy> =
        roots.flatMap { root ->
            fs.listOrNull(root).orEmpty().mapNotNull { describe(it, protected) }
        }.distinctBy(InstanceCopy::path).sortedWith(compareBy({ it.kind }, { it.path.toString() }))

    fun delete(path: Path, roots: Set<Path>, protected: Map<Path, String>) {
        check(path.parent in roots && fs.canonicalize(path) == path) { "The copy is outside the known instance directories." }
        val copy = checkNotNull(describe(path, protected)) { "This folder is no longer a managed copy." }
        check(copy.protection == null) { copy.protection.orEmpty() }
        deleteTree(path)
    }

    private fun describe(path: Path, protected: Map<Path, String>): InstanceCopy? {
        val kind = when {
            hasCopyName(path, BACKUP_PREFIX) -> InstanceCopyKind.BACKUP
            hasCopyName(path, DISPLACED_PREFIX) -> InstanceCopyKind.DISPLACED
            hasCopyName(path, TEST_INSTANCE_PREFIX) -> InstanceCopyKind.TEST
            hasCopyName(path, STAGING_PREFIX) -> InstanceCopyKind.STAGING
            else -> return null
        }
        val metadata = fs.metadataOrNull(path) ?: return null
        if (!metadata.isDirectory || metadata.symlinkTarget != null) return null
        val hasManifest = fs.metadataOrNull(path / PREPARATION_MANIFEST)?.isRegularFile == true
        val recognizable = when (kind) {
            InstanceCopyKind.STAGING -> hasManifest || instanceGameDirectory(fs, path / "instance") != null
            InstanceCopyKind.TEST -> hasManifest || instanceGameDirectory(fs, path) != null
            InstanceCopyKind.BACKUP, InstanceCopyKind.DISPLACED -> instanceGameDirectory(fs, path) != null
        }
        if (!recognizable) return null
        val protection = protected.entries.firstOrNull { (entry, _) ->
            generateSequence(entry, Path::parent).any { it == path }
        }?.value
        return InstanceCopy(path, kind, protection)
    }

    private fun hasCopyName(path: Path, prefix: String): Boolean {
        if (!path.name.startsWith(prefix)) return false
        val suffix = path.name.removePrefix(prefix)
        return Uuid.parseOrNull(suffix)?.toString() == suffix
    }

    private fun deleteTree(path: Path) {
        val metadata = fs.metadataOrNull(path) ?: return
        if (metadata.isDirectory && metadata.symlinkTarget == null) {
            for (child in fs.list(path)) deleteTree(child)
        }
        fs.delete(path)
    }
}
