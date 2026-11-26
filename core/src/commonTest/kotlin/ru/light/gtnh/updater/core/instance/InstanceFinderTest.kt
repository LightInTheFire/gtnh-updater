package ru.light.gtnh.updater.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path

class InstanceFinderTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun discoversDefaultPrismFlatpakAndMultimcInstances() = withWorkspace { home ->
        val prism = createInstance(home / ".local/share/PrismLauncher/instances/first", "Prism GTNH")
        val flatpak = createInstance(
            home / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/flatpack",
            "Flatpak GTNH",
        )
        val multiMc = createInstance(home / ".config/local/multimc/instances/third", "MultiMC GTNH")
        val finder = InstanceFinder(environment = mapOf("HOME" to home.toString())::get)

        val instances = finder.discover()

        assertEquals(
            mapOf(
                fs.canonicalize(prism) to "Prism Launcher",
                fs.canonicalize(flatpak) to "Prism Launcher",
                fs.canonicalize(multiMc) to "MultiMC",
            ),
            instances.associate { it.directory to it.launcher },
        )
        assertEquals(listOf("Flatpak GTNH", "MultiMC GTNH", "Prism GTNH"), instances.map { it.name })
    }

    @Test
    fun readsConfiguredInstanceFoldersInBothLaunchers() = withWorkspace { home ->
        val share = home / "custom data"
        val prismRoot = share / "PrismLauncher"
        val multiMcRoot = home / ".config/local/multimc"
        val external = home / "External Instances"
        val prism = createInstance(external / "GTNH beta", "GTNH beta")
        val multiMc = createInstance(multiMcRoot / "relocated/MultiMC pack", "MultiMC pack")
        fs.createDirectories(prismRoot)
        fs.write(prismRoot / "prismlauncher.cfg") { writeUtf8("InstanceDir=$external\n") }
        fs.write(multiMcRoot / "multimc.cfg") { writeUtf8("InstanceDir=relocated\n") }
        val variables = mapOf("HOME" to home.toString(), "XDG_DATA_HOME" to share.toString())

        val actual = InstanceFinder(environment = variables::get).discover().map { it.directory }.toSet()

        assertEquals(setOf(fs.canonicalize(prism), fs.canonicalize(multiMc)), actual)
    }

    @Test
    fun fuzzilySearchesFoldersOrResolvesAPastedPath() = withWorkspace { home ->
        val library = home / "Game instances"
        val instance = createInstance(library / "GregTech New Horizons", "GTNH")
        fs.createDirectories(library / "Vanilla")
        val finder = InstanceFinder(environment = mapOf("HOME" to home.toString())::get)

        assertEquals(listOf(instance), finder.searchFolders(library, "gtnwhr"))
        assertEquals(listOf(instance), finder.searchFolders(home, instance.toString()))
        assertEquals(listOf(instance), finder.searchFolders(home, (library / "gtnwhr").toString()))
        assertEquals("GTNH", finder.instance(instance)?.name)
        assertNull(finder.instance(library / "Vanilla"))
    }

    private fun createInstance(path: Path, name: String): Path {
        fs.createDirectories(path / ".minecraft")
        fs.write(path / "instance.cfg") { writeUtf8("name=$name\n") }
        fs.write(path / "mmc-pack.json") { writeUtf8("{}") }
        return path
    }

    private fun withWorkspace(test: (Path) -> Unit) {
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-instance-finder-${Uuid.random()}"
        try {
            fs.createDirectories(home)
            test(home)
        } finally {
            fs.deleteRecursively(home)
        }
    }
}
