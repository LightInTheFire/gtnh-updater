package ru.light.gtnh.updater.core.instance

import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use
import ru.light.gtnh.updater.core.model.PreparationProgress
import ru.light.gtnh.updater.core.config.ModInventory
import ru.light.gtnh.updater.core.config.removeDefaultServersMod
import ru.light.gtnh.updater.core.model.InstalledInstance
import ru.light.gtnh.updater.core.model.PreparedInstance

internal const val INSTANCE_CONFIG = "instance.cfg"
private const val COMPONENT_MANIFEST = "mmc-pack.json"
private const val INSTANCE_DIRECTORY = "instance"
internal const val PREPARATION_MANIFEST = "prepared-update.json"
private const val COPY_BUFFER_BYTES = 64L * 1024
private const val MAX_VERSION_CONFIG_BYTES = 64L * 1024
private const val VERSION_CONFIG_PATH = "config/GTNewHorizons/dreamcraft.cfg"
private val GAME_DIRECTORIES = listOf(".minecraft", "minecraft")
private val PACK_VERSION_PROPERTY = Regex("""^\s*S:ModPackVersion\s*=\s*"?([^"\r\n]+?)"?\s*$""")
private val UNSAFE_PATH_CHARACTERS = setOf('/', '\\', ':', '<', '>', '"', '|', '?', '*')
private val WINDOWS_DEVICE_NAME = Regex("""(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])""", RegexOption.IGNORE_CASE)

internal class InstancePreparer(private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    suspend fun prepare(
        installedPath: Path,
        archivePath: Path,
        targetVersion: String? = null,
        onProgress: (PreparationProgress) -> Unit = {},
    ): PreparedInstance {
        val installed = fileSystem.canonicalize(installedPath)
        val gameDirectory = instanceGameDirectory(fileSystem, installed)
            ?: error("Choose a Prism/MultiMC instance folder containing $INSTANCE_CONFIG and $COMPONENT_MANIFEST.")
        val parent = checkNotNull(installed.parent) { "The installed instance needs a parent directory." }
        val currentVersion = readVersion(fileSystem, gameDirectory)

        return extractClient(archivePath, parent, targetVersion, onProgress) { client ->
            val plan = PreparedInstance(installed, client.instance, currentVersion, client.version, client.shippedConfigs)
            writePreparedPlan(fileSystem, client.staging, plan)
            plan
        }
    }

    suspend fun install(
        archivePath: Path,
        instancesDirectory: Path,
        targetVersion: String,
        removeDefaultServerList: Boolean,
        onProgress: (PreparationProgress) -> Unit = {},
    ): InstalledInstance {
        require(instancesDirectory.isAbsolute) { "Enter the full path to your launcher instances folder." }
        val parent = fileSystem.canonicalize(instancesDirectory)
        require(fileSystem.metadata(parent).isDirectory && instanceGameDirectory(fileSystem, parent) == null) {
            "Choose a launcher instances folder."
        }
        require(isPortablePathComponent(targetVersion)) { "Invalid GTNH version." }
        return extractClient(archivePath, parent, targetVersion, onProgress) { client ->
            if (removeDefaultServerList) removeDefaultServersMod(fileSystem, client.game)
            val names = PrismInstanceName(fileSystem)
            val existing = fileSystem.list(parent).mapNotNull(names::get).map(String::lowercase).toSet()
            val baseName = "GTNH $targetVersion"
            val name = sequenceOf(baseName).plus(generateSequence(2) { it + 1 }.map { "$baseName ($it)" })
                .first { it.lowercase() !in existing }
            names.set(client.instance, name)
            val destination = parent / "GTNH-$targetVersion-${Uuid.random()}"
            check(fileSystem.metadataOrNull(destination) == null) { "The installation path already exists." }
            currentCoroutineContext().ensureActive()
            fileSystem.atomicMove(client.instance, destination)
            runCatching { fileSystem.delete(client.staging) }
            InstalledInstance(destination, name)
        }
    }

    private suspend fun <T> extractClient(
        archivePath: Path,
        parent: Path,
        targetVersion: String?,
        onProgress: (PreparationProgress) -> Unit,
        finish: suspend (ExtractedClient) -> T,
    ): T = fileSystem.openZip(archivePath).use { zip ->
        val packRoot = findPackRoot(zip)
        val targetGameDirectory = checkNotNull(instanceGameDirectory(zip, packRoot))
        val resolvedTargetVersion = targetVersion ?: readVersion(zip, targetGameDirectory)
        val entries = zip.listRecursively(packRoot).toList()
        val stagingDirectory = parent / ".gtnh-updater-${Uuid.random()}"
        val candidate = stagingDirectory / INSTANCE_DIRECTORY
        val shippedConfigs = entries.asSequence()
            .filter { it.toString().startsWith("$targetGameDirectory/") && zip.metadata(it).isRegularFile }
            .map { it.relativeTo(targetGameDirectory).segments }
            .filter { it.firstOrNull() in setOf("config", "configs") }
            .map { it.joinToString("/") }
            .toSet()
        currentCoroutineContext().ensureActive()
        fileSystem.createDirectory(stagingDirectory, mustCreate = true)
        var complete = false
        try {
            fileSystem.createDirectory(candidate, mustCreate = true)
            onProgress(PreparationProgress(totalEntries = entries.size))
            for ((index, entry) in entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                val relative = entry.relativeTo(packRoot)
                val destination = relative.segments.fold(candidate) { path, segment ->
                    check(isPortablePathComponent(segment)) { "The ZIP contains an unsupported archive path." }
                    path / segment
                }
                val metadata = zip.metadata(entry)
                when {
                    metadata.isDirectory -> fileSystem.createDirectory(destination, mustCreate = true)
                    metadata.isRegularFile -> copyFile(zip, entry, destination)
                    else -> error("The ZIP contains an unsupported archive entry.")
                }
                onProgress(PreparationProgress(index + 1, entries.size))
            }
            currentCoroutineContext().ensureActive()
            val game = candidate / targetGameDirectory.relativeTo(packRoot)
            ModInventory(fileSystem).storeBaseline(candidate, game)
            currentCoroutineContext().ensureActive()
            val result = finish(ExtractedClient(stagingDirectory, candidate, game, resolvedTargetVersion, shippedConfigs))
            complete = true
            result
        } finally {
            if (!complete) fileSystem.deleteRecursively(stagingDirectory)
        }
    }

    private data class ExtractedClient(
        val staging: Path,
        val instance: Path,
        val game: Path,
        val version: String?,
        val shippedConfigs: Set<String>,
    )

    private fun findPackRoot(zip: FileSystem): Path {
        val root = "/".toPath()
        if (instanceGameDirectory(zip, root) != null) return root
        return zip.list(root).singleOrNull { instanceGameDirectory(zip, it) != null }
            ?: error("The ZIP must contain exactly one Prism/MultiMC client instance.")
    }

    private suspend fun copyFile(zip: FileSystem, entry: Path, destination: Path) {
        zip.source(entry).use { source ->
            fileSystem.sink(destination, mustCreate = true).use { sink ->
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer, COPY_BUFFER_BYTES)
                    if (read < 0L) break
                    sink.write(buffer, read)
                }
            }
        }
    }

}

internal fun writePreparedPlan(fileSystem: FileSystem, directory: Path, plan: PreparedInstance) {
    val json = buildJsonObject {
        put("source", plan.source.toString())
        put("candidate", plan.candidate.relativeTo(directory).toString())
        plan.currentVersion?.let { put("currentVersion", it) }
        plan.targetVersion?.let { put("targetVersion", it) }
    }
    val temporary = directory / ".$PREPARATION_MANIFEST-${Uuid.random()}.tmp"
    try {
        fileSystem.write(temporary, mustCreate = true) { writeUtf8(json.toString()) }
        fileSystem.atomicMove(temporary, directory / PREPARATION_MANIFEST)
    } finally {
        fileSystem.delete(temporary)
    }
}

internal fun instanceGameDirectory(fileSystem: FileSystem, root: Path): Path? {
    if (fileSystem.metadataOrNull(root)?.isDirectory != true) return null
    if (fileSystem.metadataOrNull(root / INSTANCE_CONFIG)?.isRegularFile != true ||
        fileSystem.metadataOrNull(root / COMPONENT_MANIFEST)?.isRegularFile != true
    ) return null
    return GAME_DIRECTORIES.asSequence().map { root / it }
        .firstOrNull { fileSystem.metadataOrNull(it)?.isDirectory == true }
}

private fun readVersion(fileSystem: FileSystem, gameDirectory: Path): String? {
    val path = gameDirectory / VERSION_CONFIG_PATH
    val metadata = fileSystem.metadataOrNull(path) ?: return null
    if (!metadata.isRegularFile || (metadata.size ?: return null) > MAX_VERSION_CONFIG_BYTES) return null
    return fileSystem.read(path) {
        generateSequence { readUtf8Line() }.firstNotNullOfOrNull { line ->
            PACK_VERSION_PROPERTY.matchEntire(line)?.groupValues?.get(1)
        }
    }
}

private fun isPortablePathComponent(segment: String): Boolean =
    segment.isNotEmpty() && !segment.endsWith('.') && !segment.endsWith(' ') &&
        segment.none { it.isISOControl() || it in UNSAFE_PATH_CHARACTERS } &&
        !WINDOWS_DEVICE_NAME.matches(segment.substringBefore('.'))
