package ru.light.gtnh.updater.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.instance.PrismTestLauncher
import ru.light.gtnh.updater.core.instance.UpdateSessionStore
import ru.light.gtnh.updater.core.instance.writePreparedPlan
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.PrismProfile
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage
import ru.light.gtnh.updater.core.settings.SettingsStore

class UpdateFlowTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun logsRecoveryFailuresWithoutLeakingSavedContent() = fixture { home, _ ->
        val updater = Updater("0.2.2", home, PrismTestLauncher(), SettingsStore(home))
        write(home / "active-update.json", "local-sensitive-content")
        write(home / "last-switch.json", "local-sensitive-content")

        assertFailsWith<IllegalArgumentException> { updater.resumeUpdate() }
        assertFailsWith<IllegalArgumentException> { updater.lastSwitch() }
        val log = read(home / "updater.log")
        assertTrue("operation=update.resume failed type=" in log)
        assertTrue("operation=update.last-switch failed type=" in log)
        assertFalse("local-sensitive-content" in log)
    }

    @Test
    fun rejectsTestActionsForAnOldSession() = fixture { home, prepared ->
        val launcher = PrismTestLauncher(start = { _, _ -> error("Unexpected launcher start") })
        val updater = Updater(null, home, launcher, SettingsStore(home))
        val profile = PrismProfile(home, checkNotNull(prepared.source.parent), flatpak = false)
        val registered = updater.registerPrismTest(updater.beginUpdate(prepared), profile)
        val current = updater.selectNewConfigs(registered)

        assertFailsWith<IllegalStateException> { updater.launchPrismTest(registered, profile) }
        val staleTest = UpdateSession(registered.prepared, UpdateStage.TEST_STARTED)
        assertFailsWith<IllegalStateException> { updater.confirmTestClosed(staleTest) }
        assertEquals(current.stage, updater.resumeUpdate()?.stage)
    }

    @Test
    fun recoversInterruptedRegistrationThenMigratesAndRollsBackWithoutMinecraft() = fixture { home, prepared ->
        val launcher = PrismTestLauncher(start = { _, _ -> error("Unexpected launcher start") })
        val updater = Updater(null, home, launcher, SettingsStore(home))
        val initial = updater.beginUpdate(prepared)
        val destination = launcher.registrationDestination(prepared, checkNotNull(prepared.source.parent))
        UpdateSessionStore(fs, home, "pending-registration.json").save(
            UpdateSession(prepared, UpdateStage.NEW_CONFIGS_SELECTED, destination),
        )
        fs.atomicMove(prepared.candidate, destination)
        val moved = prepared.copy(candidate = destination)
        launcher.labelCandidate(moved)
        writePreparedPlan(fs, destination, moved)

        val restarted = Updater(null, home, launcher, SettingsStore(home))
        val recovered = checkNotNull(restarted.resumeUpdate())
        assertEquals(initial.prepared, recovered.prepared)
        assertEquals(initial.stage, recovered.stage)
        assertFalse(fs.exists(destination))
        assertFalse(fs.exists(home / "pending-registration.json"))

        val ready = restarted.selectNewConfigs(recovered)
        val review = restarted.reviewPlayerData(ready)
        restarted.migratePlayerData(ready, review, emptySet()) {}
        val activated = restarted.activateUpdate(ready)
        assertEquals("new", read(prepared.source / ".minecraft/config/pack.cfg"))
        assertEquals("new settings", read(prepared.source / ".minecraft/options.txt"))
        assertEquals("world", read(prepared.source / ".minecraft/saves/world/level.dat"))
        assertTrue(fs.exists(activated.prepared.candidate))
        assertEquals(UpdateStage.ROLLED_BACK, restarted.rollbackUpdate(activated).stage)
        assertEquals("old", read(prepared.source / ".minecraft/config/pack.cfg"))
    }

    @Test
    fun keepsRegisteredCandidateIfSessionWasSavedBeforeInterruption() = fixture { home, prepared ->
        val launcher = PrismTestLauncher()
        val updater = Updater(null, home, launcher, SettingsStore(home))
        val initial = updater.beginUpdate(prepared)
        val registered = updater.selectNewConfigs(initial)
        UpdateSessionStore(fs, home, "pending-registration.json").save(
            UpdateSession(prepared, registered.stage, registered.prepared.candidate),
        )

        val recovered = checkNotNull(updater.resumeUpdate())
        assertEquals(registered.prepared, recovered.prepared)
        assertEquals(registered.stage, recovered.stage)
        assertFalse(fs.exists(home / "pending-registration.json"))
    }

    private fun fixture(test: suspend (Path, PreparedInstance) -> Unit) = runBlocking {
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-update-test-${Uuid.random()}"
        val source = home / "instances/current"
        val staging = home / "instances/.gtnh-updater-${Uuid.random()}"
        val candidate = staging / "instance"
        try {
            for ((path, version) in listOf(source to "old", candidate to "new")) {
                write(path / "instance.cfg", "name=$version")
                write(path / "mmc-pack.json", "{}")
                write(path / ".minecraft/config/pack.cfg", version)
                write(path / ".minecraft/options.txt", "$version settings")
            }
            write(source / ".minecraft/saves/world/level.dat", "world")
            val prepared = PreparedInstance(source, candidate, "2.9.1", "2.9.2")
            writePreparedPlan(fs, staging, prepared)
            test(home, prepared)
        } finally {
            fs.deleteRecursively(home)
        }
    }

    private fun write(path: Path, value: String) {
        fs.createDirectories(checkNotNull(path.parent))
        fs.write(path) { writeUtf8(value) }
    }

    private fun read(path: Path): String = fs.read(path) { readUtf8() }
}
