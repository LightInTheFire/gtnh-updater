package ru.light.gtnh.updater.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.InstanceCopyKind

class InstanceCopiesTest {
    private val fs = FileSystem.SYSTEM
    private val manager = InstanceCopies(fs)

    @Test
    fun listsManagedCopiesAndProtectsAnActiveStagingParentAndRollbackBackup() = fixture { root ->
        val stage = root / "${STAGING_PREFIX}${Uuid.random()}"
        val pending = stage / "instance"
        val backup = root / "${BACKUP_PREFIX}${Uuid.random()}"
        val displaced = root / "${DISPLACED_PREFIX}${Uuid.random()}"
        val test = root / "${TEST_INSTANCE_PREFIX}${Uuid.random()}"
        val original = root / "GTNH"
        val unrelated = root / "${TEST_INSTANCE_PREFIX}custom"
        for (path in listOf(pending, backup, displaced, test, original, unrelated)) createInstance(path)
        val protected = mapOf(pending to "current update", backup to "rollback")

        val copies = manager.list(setOf(root), protected).associateBy { it.path }
        assertEquals(setOf(stage, backup, displaced, test), copies.keys)
        assertEquals(InstanceCopyKind.STAGING, copies[stage]?.kind)
        assertEquals("current update", copies[stage]?.protection)
        assertEquals("rollback", copies[backup]?.protection)

        assertFailsWith<IllegalStateException> { manager.delete(stage, setOf(root), protected) }
        assertFailsWith<IllegalStateException> { manager.delete(backup, setOf(root), protected) }
        assertFailsWith<IllegalStateException> { manager.delete(original, setOf(root), protected) }
        assertFailsWith<IllegalStateException> { manager.delete(displaced, emptySet(), protected) }
        manager.delete(displaced, setOf(root), protected)
        assertFalse(fs.exists(displaced))
        assertTrue(fs.exists(pending / "instance.cfg"))
        assertTrue(fs.exists(original / "instance.cfg"))
        assertTrue(fs.exists(backup / "instance.cfg"))
        assertTrue(fs.exists(test / "instance.cfg"))
    }

    @Test
    fun deletesAnOldStagingDirectoryContainingOnlyItsPlan() = fixture { root ->
        val stage = root / "${STAGING_PREFIX}${Uuid.random()}"
        fs.createDirectory(stage)
        fs.write(stage / PREPARATION_MANIFEST) { writeUtf8("{}") }
        assertEquals(InstanceCopyKind.STAGING, manager.list(setOf(root), emptyMap()).single().kind)
        manager.delete(stage, setOf(root), emptyMap())
        assertFalse(fs.exists(stage))
    }

    private fun createInstance(path: Path) {
        fs.createDirectories(path / ".minecraft")
        fs.write(path / "instance.cfg") { writeUtf8("name=GTNH") }
        fs.write(path / "mmc-pack.json") { writeUtf8("{}") }
    }

    private fun fixture(test: (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-copy-test-${Uuid.random()}"
        try {
            fs.createDirectory(directory)
            test(fs.canonicalize(directory))
        } finally {
            fs.deleteRecursively(directory)
        }
    }
}
