package ru.light.gtnh.updater.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.config.MIGRATION_MARKER
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage

class InstanceSwitchTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun activatesAtTheOriginalPathAndPreservesProgressOnRollback() = fixture { home, prepared ->
        val updates = UpdateSessionStore(fs, home)
        val switching = InstanceSwitch(fs, home, updates)
        val marker = prepared.candidate / ".minecraft" / MIGRATION_MARKER
        fs.delete(marker)
        val ready = UpdateSession(prepared, UpdateStage.TEST_CONFIRMED)
        updates.save(ready)
        assertFailsWith<IllegalStateException> { switching.activate(ready) }
        fs.write(marker) { writeUtf8("migrated") }
        fs.write(prepared.source / "instance.cfg") { writeUtf8("name=Old pack\r\ncustom=value\r\n") }
        fs.write(prepared.candidate / "instance.cfg") { writeUtf8("name=New pack\n") }
        PrismInstanceName(fs).label(prepared.candidate, "Test", prepared.targetVersion)

        val activated = switching.activate(ready)
        assertEquals(UpdateStage.ACTIVATED, activated.stage)
        assertEquals("new", world(prepared.source))
        assertEquals("old", world(activated.prepared.candidate))
        assertEquals("name=New pack\n", fs.read(prepared.source / "instance.cfg") { readUtf8() })
        assertEquals(
            "name=[Backup 2.9.1] Old pack\r\ncustom=value\r\n",
            fs.read(activated.prepared.candidate / "instance.cfg") { readUtf8() },
        )
        assertFalse(fs.exists(prepared.candidate))
        putWorld(prepared.source, "progress after updating")

        val restarted = InstanceSwitch(fs, home, updates)
        val rollback = restarted.rollback(checkNotNull(restarted.lastSwitch()))
        assertEquals(UpdateStage.ROLLED_BACK, rollback.stage)
        assertEquals("old", world(prepared.source))
        assertEquals("progress after updating", world(rollback.prepared.candidate))
        assertEquals("name=Old pack\r\ncustom=value\r\n", fs.read(prepared.source / "instance.cfg") { readUtf8() })
        assertEquals(
            "name=[After rollback 2.9.2] New pack\n",
            fs.read(rollback.prepared.candidate / "instance.cfg") { readUtf8() },
        )
        assertEquals(rollback.prepared, updates.load()?.prepared)
    }

    @Test
    fun restoresTheActivePathIfInterruptedAfterTheFirstMove() = fixture { home, prepared ->
        val updates = UpdateSessionStore(fs, home)
        val journal = UpdateSessionStore(fs, home, "pending-switch.json")
        val displaced = prepared.source.parent!! / "${BACKUP_PREFIX}fixture"
        updates.save(UpdateSession(prepared, UpdateStage.MIGRATED_TEST_CONFIRMED))
        journal.save(UpdateSession(prepared, UpdateStage.ACTIVATING, displaced))
        fs.atomicMove(prepared.source, displaced)

        InstanceSwitch(fs, home, updates).recover()

        assertEquals("old", world(prepared.source))
        assertEquals("new", world(prepared.candidate))
        assertFalse(fs.exists(displaced))
        assertFalse(fs.exists(home / "pending-switch.json"))
        assertEquals(UpdateStage.MIGRATED_TEST_CONFIRMED, updates.load()?.stage)
    }

    @Test
    fun recoversSwitchRecordsIfInterruptedAfterBothMoves() = fixture { home, prepared ->
        val updates = UpdateSessionStore(fs, home)
        val journal = UpdateSessionStore(fs, home, "pending-switch.json")
        val displaced = prepared.source.parent!! / "${BACKUP_PREFIX}fixture"
        updates.save(UpdateSession(prepared, UpdateStage.MIGRATED_TEST_CONFIRMED))
        journal.save(UpdateSession(prepared, UpdateStage.ACTIVATING, displaced))
        fs.atomicMove(prepared.source, displaced)
        fs.atomicMove(prepared.candidate, prepared.source)

        val result = checkNotNull(InstanceSwitch(fs, home, updates).lastSwitch())

        assertEquals(UpdateStage.ACTIVATED, result.stage)
        assertEquals(displaced, result.prepared.candidate)
        assertEquals("old", world(displaced))
        assertEquals("new", world(prepared.source))
        assertTrue(updates.load()?.stage == UpdateStage.ACTIVATED)
        assertFalse(fs.exists(home / "pending-switch.json"))
    }

    private fun fixture(test: (Path, PreparedInstance) -> Unit) {
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-switch-test-${Uuid.random()}"
        val source = home / "instances/current"
        val candidate = home / "instances/${TEST_INSTANCE_PREFIX}fixture"
        try {
            for (instance in listOf(source, candidate)) {
                fs.createDirectories(instance / ".minecraft/saves")
                fs.write(instance / "instance.cfg") { writeUtf8("name=GTNH") }
                fs.write(instance / "mmc-pack.json") { writeUtf8("{}") }
            }
            putWorld(source, "old")
            putWorld(candidate, "new")
            fs.write(candidate / ".minecraft" / MIGRATION_MARKER) { writeUtf8("migrated") }
            test(home, PreparedInstance(source, candidate, "2.9.1", "2.9.2"))
        } finally {
            fs.deleteRecursively(home)
        }
    }

    private fun putWorld(instance: Path, contents: String) =
        fs.write(instance / ".minecraft/saves/progress") { writeUtf8(contents) }

    private fun world(instance: Path): String = fs.read(instance / ".minecraft/saves/progress") { readUtf8() }
}
