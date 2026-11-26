package ru.light.gtnh.updater.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.PrismProfile

class PrismTestLauncherTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun launchesTheRegisteredCandidateInTheOriginalPrismProfile() {
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-prism-test-${Uuid.random()}"
        try {
            val root = home / ".local/share/PrismLauncher"
            val instances = root / "My Instances"
            val source = instances / "Current GTNH"
            fs.createDirectories(source / ".minecraft")
            for ((path, text) in mapOf(
                root / "prismlauncher.cfg" to "InstanceDir=My Instances",
                source / "instance.cfg" to "name=Installed GTNH",
                source / "mmc-pack.json" to "{}",
                source / ".minecraft/world.dat" to "world",
            )) {
                fs.write(path) { writeUtf8(text) }
            }
            val finder = InstanceFinder(environment = mapOf("HOME" to home.toString())::get)
            val profile = checkNotNull(finder.prismProfileFor(source))
            val staging = profile.instances / ".gtnh-updater-fixture"
            val candidate = staging / "instance"
            fs.createDirectories(candidate)
            fs.write(candidate / "instance.cfg") { writeUtf8("name=Clean GTNH") }
            fs.write(candidate / "mmc-pack.json") { writeUtf8("{}") }
            fs.createDirectories(candidate / ".minecraft/mods")
            fs.write(candidate / ".minecraft/mods/defaultserverlist-1.8.1.jar") { writeUtf8("fixture") }
            fs.write(candidate / ".minecraft/mods/other.jar") { writeUtf8("fixture") }
            val prepared = PreparedInstance(fs.canonicalize(source), candidate, "2.9.1", "2.9.2")
            writePreparedPlan(fs, staging, prepared)
            val requests = mutableListOf<Pair<PrismProfile, List<String>>>()
            val launcher = PrismTestLauncher(start = { selected, arguments -> requests += selected to arguments })

            assertFailsWith<IllegalStateException> {
                launcher.register(prepared, profile) { error("Could not save active update") }
            }
            assertTrue(fs.exists(candidate / "instance.cfg"))
            assertEquals("name=Clean GTNH", fs.read(candidate / "instance.cfg") { readUtf8() })
            assertTrue(fs.exists(staging / PREPARATION_MANIFEST))
            assertFalse(fs.exists(candidate / PREPARATION_MANIFEST))

            val test = launcher.register(prepared, profile)
            launcher.launch(test, profile, removeDefaultServerList = true)

            assertEquals(profile.instances / "gtnh-updater-test-fixture", test.candidate)
            assertFalse(fs.exists(staging))
            assertTrue(fs.exists(test.candidate / "instance.cfg"))
            assertEquals("name=[Test 2.9.2] Clean GTNH", fs.read(test.candidate / "instance.cfg") { readUtf8() })
            assertFalse(fs.exists(test.candidate / ".minecraft/mods/defaultserverlist-1.8.1.jar"))
            assertTrue(fs.exists(test.candidate / ".minecraft/mods/other.jar"))
            assertEquals("world", fs.read(source / ".minecraft/world.dat") { readUtf8() })
            val manifest = fs.read(test.candidate / "prepared-update.json") { Json.parseToJsonElement(readUtf8()).jsonObject }
            assertEquals(".", manifest.getValue("candidate").jsonPrimitive.content)
            assertEquals(listOf(profile to listOf("--dir", profile.root.toString(), "--launch", test.candidate.name)), requests)
        } finally {
            fs.deleteRecursively(home)
        }
    }
}
