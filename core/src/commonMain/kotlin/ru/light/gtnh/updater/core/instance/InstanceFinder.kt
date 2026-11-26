package ru.light.gtnh.updater.core.instance

import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import ru.light.gtnh.updater.core.InstanceBrowser
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.model.InstanceChoice
import ru.light.gtnh.updater.core.model.PrismProfile
import ru.light.gtnh.updater.core.platform.readEnvironmentVariable

private const val INSTANCES_DIRECTORY = "instances"
private const val PRISM_CONFIG = "prismlauncher.cfg"
private const val MULTIMC_CONFIG = "multimc.cfg"
private const val INSTANCE_DIRECTORY_SETTING = "InstanceDir"
private const val INSTANCE_NAME_SETTING = "name"
private const val PRISM_NAME = "Prism Launcher"
private const val PRISM_FLATPAK_DIRECTORY = ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"
private const val MAX_SETTINGS_BYTES = 1024L * 1024

internal class InstanceFinder(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val environment: (String) -> String? = ::readEnvironmentVariable,
) : InstanceBrowser {
    private fun envPath(name: String): Path? =
        environment(name)?.takeIf { it.isNotBlank() }?.toPath()?.takeIf { it.isAbsolute }

    private val home = envPath("HOME") ?: envPath("USERPROFILE")
    private val lastFolderListing = MutableStateFlow<Pair<Path, List<Path>>?>(null)

    override fun startingDirectory(): Path = home ?: fs.canonicalize(".".toPath())

    private val share = envPath("XDG_DATA_HOME") ?: home?.div(".local/share")
    private val appData = envPath("APPDATA")

    private fun prismRoots(): List<Path> = listOfNotNull(
        envPath("PRISMLAUNCHER_DATA_DIR"),
        share?.div("PrismLauncher"),
        appData?.div("PrismLauncher"),
        home?.div("Library/Application Support/PrismLauncher"),
        home?.div(PRISM_FLATPAK_DIRECTORY),
        home?.div("scoop/persist/prismlauncher"),
        home?.div("PrismLauncher"),
    ).distinct()

    fun prismProfileFor(instance: Path): PrismProfile? {
        val instanceParent = readable { fs.canonicalize(instance) }?.parent ?: return null
        val flatpakRoot = home?.div(PRISM_FLATPAK_DIRECTORY)?.let { readable { fs.canonicalize(it) } }
        for (candidate in prismRoots()) {
            val root = readable { fs.canonicalize(candidate) } ?: continue
            if (readable { fs.metadataOrNull(root / PRISM_CONFIG) }?.isRegularFile != true) continue
            val instances = configuredInstances(root, PRISM_CONFIG) ?: root / INSTANCES_DIRECTORY
            val canonicalInstances = readable { fs.canonicalize(instances) } ?: continue
            if (canonicalInstances == instanceParent) return PrismProfile(root, canonicalInstances, root == flatpakRoot)
        }
        return null
    }

    private fun configuredInstances(root: Path, configName: String): Path? =
        setting(root / configName, INSTANCE_DIRECTORY_SETTING)?.let { root / it.removeSurrounding("\"") }

    private fun launcherRoots(): List<Triple<Path?, String, String>> {
        val multiMc = "MultiMC"
        val config = envPath("XDG_CONFIG_HOME") ?: home?.div(".config")
        return prismRoots().map { Triple(it, PRISM_CONFIG, PRISM_NAME) } + listOf(
            Triple(config?.div("local/multimc"), MULTIMC_CONFIG, multiMc),
            Triple(home?.div(".config/local/multimc"), MULTIMC_CONFIG, multiMc),
            Triple(share?.div("multimc"), MULTIMC_CONFIG, multiMc),
            Triple(appData?.div("MultiMC"), MULTIMC_CONFIG, multiMc),
            Triple(home?.div("Library/Application Support/MultiMC"), MULTIMC_CONFIG, multiMc),
            Triple(home?.div("Applications/MultiMC.app/Data"), MULTIMC_CONFIG, multiMc),
            Triple(home?.div("MultiMC"), MULTIMC_CONFIG, multiMc),
            Triple(home?.div("multimc"), MULTIMC_CONFIG, multiMc),
        )
    }

    fun instanceDirectories(): Set<Path> = launcherRoots().flatMap { (root, config, _) ->
        if (root == null) emptyList() else instanceDirectories(root, config)
    }.mapNotNull { directory -> readable { fs.canonicalize(directory).takeIf(::isDirectory) } }.toSet()

    private fun instanceDirectories(root: Path, config: String): List<Path> =
        listOfNotNull(configuredInstances(root, config), root / INSTANCES_DIRECTORY).distinct()

    override fun discover(): List<InstanceChoice> {
        val found = linkedMapOf<Path, InstanceChoice>()
        for ((root, configName, launcher) in launcherRoots()) {
            if (root == null) continue
            for (directory in instanceDirectories(root, configName)) {
                for (entry in directories(directory)) {
                    if (entry.name.startsWith(BACKUP_PREFIX) || entry.name.startsWith(DISPLACED_PREFIX)) continue
                    val choice = instance(entry, launcher) ?: continue
                    if (choice.directory !in found) found[choice.directory] = choice
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    override fun instance(path: Path): InstanceChoice? = instance(path, launcher = null)

    private fun instance(path: Path, launcher: String?): InstanceChoice? = readable {
        val canonical = fs.canonicalize(path)
        if (instanceGameDirectory(fs, canonical) == null) return@readable null
        val name = setting(canonical / INSTANCE_CONFIG, INSTANCE_NAME_SETTING)?.takeIf { it.isNotBlank() } ?: path.name
        InstanceChoice(canonical, name, launcher)
    }

    override fun searchFolders(directory: Path, query: String): List<Path> {
        val absolute = resolveAbsolutePath(query)
        if (absolute != null) {
            if (isDirectory(absolute)) return listOf(absolute)
            val parent = absolute.parent ?: return emptyList()
            return fuzzySearch(browsableDirectories(parent), absolute.name) { listOf(it.name) }
        }
        return fuzzySearch(browsableDirectories(directory), query) { listOf(it.name) }
    }

    private fun browsableDirectories(path: Path): List<Path> {
        lastFolderListing.value?.takeIf { it.first == path }?.let { return it.second }
        return directories(path).also { lastFolderListing.value = path to it }
    }

    private fun resolveAbsolutePath(query: String): Path? {
        if (query.isBlank()) return null
        return readable {
            when {
                query.startsWith("~/") -> home?.div(query.removePrefix("~/"))
                else -> query.toPath().takeIf { it.isAbsolute }
            }
        }
    }

    private fun directories(path: Path): List<Path> =
        (readable { fs.listOrNull(path) } ?: emptyList()).filter(::isDirectory).sortedBy { it.name.lowercase() }

    private fun isDirectory(path: Path): Boolean = readable {
        val metadata = fs.metadataOrNull(path)
        metadata?.isDirectory == true ||
            (metadata?.symlinkTarget != null && fs.metadataOrNull(fs.canonicalize(path))?.isDirectory == true)
    } ?: false

    private fun setting(path: Path, key: String): String? = readable {
        val size = fs.metadataOrNull(path)?.size ?: return@readable null
        if (size > MAX_SETTINGS_BYTES) return@readable null
        fs.read(path) {
            generateSequence { readUtf8Line() }
                .firstOrNull { it.substringBefore('=').trim() == key }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun <T> readable(read: () -> T): T? = try {
        read()
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
