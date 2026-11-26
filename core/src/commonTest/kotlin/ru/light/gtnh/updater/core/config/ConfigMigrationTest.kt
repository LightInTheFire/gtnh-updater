package ru.light.gtnh.updater.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ByteString.Companion.decodeBase64
import okio.Path
import ru.light.gtnh.updater.core.instance.UpdateSessionStore
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage

class ConfigMigrationTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun independentSettingsCanBeMixedAndRememberedForTheNextCandidate() = withInstances { root, prepared ->
        val path = "config/example.cfg"
        val source = prepared.source / ".minecraft" / path
        val target = prepared.candidate / ".minecraft" / path
        val old = "start\r\nB:first=true\r\nI:second=42\r\nend"
        val new = "start\r\nB:first=false\r\nI:second=5\r\nend"
        fs.put(source, old)
        fs.put(target, new)
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)
        assertEquals(listOf(2, 3), review.changes.map { it.newLine })
        assertEquals(listOf("B:first=true"), review.changes.first().oldLines)

        val choices = mapOf(review.changes[0].id to ConfigChoice.OLD, review.changes[1].id to ConfigChoice.NEW)
        assertEquals(1, migration.apply(prepared, review, choices))
        assertEquals("start\r\nB:first=true\r\nI:second=5\r\nend", fs.text(target))
        assertEquals(old, fs.text(source))
        val history = fs.text(root / "config-choices.json")
        assertFalse(history.contains("first"))
        assertFalse(history.contains(path))

        fs.put(target, new)
        val nextRun = ConfigMigration(fs, root)
        val next = nextRun.review(prepared)
        assertEquals(listOf(ConfigChoice.OLD, ConfigChoice.NEW), next.changes.map { it.remembered })
        assertEquals(0, nextRun.apply(prepared, next, next.changes.associate { it.id to ConfigChoice.NEW }))
        assertEquals(new, fs.text(target))
        assertTrue(nextRun.review(prepared).changes.all { it.remembered == ConfigChoice.NEW })
    }

    @Test
    fun canKeepOldOnlyFilesAndRemoveNewOnlyFiles() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft/configs/old.cfg"
        val oldTarget = prepared.candidate / ".minecraft/configs/old.cfg"
        val newTarget = prepared.candidate / ".minecraft/configs/new.cfg"
        fs.put(source, "")
        fs.put(newTarget, "created = true\n")
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)
        assertEquals(listOf("configs/new.cfg", "configs/old.cfg"), review.changes.map { it.path })
        assertEquals(2, migration.apply(prepared, review, review.changes.associate { it.id to ConfigChoice.OLD }))
        assertEquals("", fs.text(oldTarget))
        assertFalse(fs.exists(newTarget))
        assertTrue(fs.exists(source))
    }

    @Test
    fun rejectsStaleReviewsBeforeWritingAndReportsBinaryFiles() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft/config/a.cfg"
        val target = prepared.candidate / ".minecraft/config/a.cfg"
        val otherSource = prepared.source / ".minecraft/config/b.cfg"
        val otherTarget = prepared.candidate / ".minecraft/config/b.cfg"
        fs.put(source, "enabled=true\n")
        fs.put(target, "enabled=false\n")
        fs.put(otherSource, "value=old\n")
        fs.put(otherTarget, "value=new\n")
        fs.write(target.parent!! / "data.bin") { writeByte(0) }
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)
        assertEquals(listOf("config/data.bin"), review.skipped)
        fs.put(otherTarget, "value=external\n")

        assertFailsWith<IllegalStateException> {
            migration.apply(prepared, review, review.changes.associate { it.id to ConfigChoice.OLD })
        }
        assertEquals("enabled=false\n", fs.text(target))
        assertEquals("value=external\n", fs.text(otherTarget))
        assertFalse(fs.exists(root / "config-choices.json"))
    }

    @Test
    fun excludesGeneratedFilesAndCreationCommentsAfterRestoringASession() = withInstances { root, instance ->
        val prepared = instance.copy(shippedConfigs = setOf("config/game.cfg", "config/mod/generated/shipped.cfg"))
        val oldGame = prepared.source / ".minecraft/config"
        val newGame = prepared.candidate / ".minecraft/config"
        fs.put(oldGame / "game.cfg", "; created yesterday\n# old comment\nvalue=old#value\n")
        fs.put(newGame / "game.cfg", "; created today\n  # new comment\nvalue=new#value\n")
        fs.put(oldGame / "mod/generated/shipped.cfg", "old")
        fs.put(newGame / "mod/generated/shipped.cfg", "new")
        fs.put(newGame / "runtime.cfg", "generated on first launch")
        UpdateSessionStore(fs, root).save(UpdateSession(prepared, UpdateStage.TEST_STARTED))

        val restored = checkNotNull(UpdateSessionStore(fs, root).load())
        assertEquals(UpdateStage.TEST_STARTED, restored.stage)
        assertEquals(null, restored.tested)
        val migration = ConfigMigration(fs, root)
        val review = migration.review(restored.prepared)
        val change = review.changes.single()
        assertEquals(listOf("value=old#value"), change.oldLines)
        assertEquals(3, change.newLine)
        migration.apply(restored.prepared, review, mapOf(change.id to ConfigChoice.OLD))
        assertEquals("; created today\n  # new comment\nvalue=old#value\n", fs.text(newGame / "game.cfg"))
        assertEquals("generated on first launch", fs.text(newGame / "runtime.cfg"))
        assertEquals("new", fs.text(newGame / "mod/generated/shipped.cfg"))
    }

    @Test
    fun mergesJsonByPropertyAndKeepsTheTargetsCreationMetadata() = withInstances { root, prepared ->
        val path = ".minecraft/config/options.json"
        fs.put(prepared.source / path, """{"graphics":{"shadows":true,"distance":10},"created":"old","onlyOld":null}""")
        val target = prepared.candidate / path
        fs.put(target, """{"created":"new","graphics":{"distance":5,"shadows":false},"onlyNew":[1,2]}""")
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)
        assertEquals(listOf("/graphics/distance", "/graphics/shadows", "/onlyNew", "/onlyOld"), review.changes.map { it.jsonPointer })
        val selected = review.changes.filter { it.jsonPointer != "/graphics/distance" }.associate { it.id to ConfigChoice.OLD }
        migration.apply(prepared, review, selected)
        val json = kotlinx.serialization.json.Json
        assertEquals(
            json.parseToJsonElement("""{"graphics":{"distance":5,"shadows":true},"created":"new","onlyOld":null}"""),
            json.parseToJsonElement(fs.text(target)),
        )
    }

    @Test
    fun migratesWorldsAndSelectedConfigsTogetherAndProtectsExistingWorlds() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft"
        val target = prepared.candidate / ".minecraft"
        fs.put(source / "saves/My World/level.dat", "original world")
        fs.put(target / "saves/My World/level.dat", "candidate world")
        fs.put(source / "servers.dat", "personal servers")
        fs.put(target / "servers.dat", "default servers")
        fs.put(source / "config/game.cfg", "value=old\n")
        fs.put(target / "config/game.cfg", "value=new\n")
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)
        val choices = mapOf(review.changes.single().id to ConfigChoice.OLD)
        assertEquals(listOf("saves", "servers.dat"), review.playerData)

        assertFailsWith<IllegalStateException> { migration.apply(prepared, review, choices, migratePlayerData = true) }
        assertEquals("value=new\n", fs.text(target / "config/game.cfg"))
        assertEquals("candidate world", fs.text(target / "saves/My World/level.dat"))
        assertFalse(fs.exists(target / MIGRATION_MARKER))

        fs.atomicMove(target / "saves/My World", target / "saves/Unrelated")
        assertEquals(MigrationResult(1, 2), migration.apply(prepared, review, choices, migratePlayerData = true))
        assertEquals("value=old\n", fs.text(target / "config/game.cfg"))
        assertEquals("original world", fs.text(target / "saves/My World/level.dat"))
        assertEquals("candidate world", fs.text(target / "saves/Unrelated/level.dat"))
        assertEquals("original world", fs.text(source / "saves/My World/level.dat"))
        assertEquals("personal servers", fs.text(target / "servers.dat"))
        assertTrue(migration.review(prepared).migrationComplete)
        assertFailsWith<IllegalStateException> { migration.apply(prepared, review, choices, migratePlayerData = true) }
    }

    @Test
    fun mergesNeiFoldersCreatedByBothInstancesWithoutTreatingThemAsWorlds() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft/saves/NEI"
        val target = prepared.candidate / ".minecraft/saves/NEI"
        fs.put(source / "shared.dat", "original player settings")
        fs.put(target / "shared.dat", "test launch defaults")
        fs.put(target / "generated.dat", "new defaults")
        val migration = ConfigMigration(fs, root)
        val review = migration.review(prepared)

        assertEquals(MigrationResult(0, 1), migration.apply(prepared, review, emptyMap(), migratePlayerData = true))
        assertEquals("original player settings", fs.text(target / "shared.dat"))
        assertEquals("new defaults", fs.text(target / "generated.dat"))
        assertEquals("original player settings", fs.text(source / "shared.dat"))
    }

    @Test
    fun transfersExtraModsAndKeepsStockAndTargetModsOutOfTheSelection() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft"
        val target = prepared.candidate / ".minecraft"
        fs.put(source / "mods/RemovedStock-1.0.jar", "removed by pack")
        ModInventory(fs).storeBaseline(prepared.source, source)
        fs.put(source / "mods/1.7.10/MyAddon-1.0.jar", "extra")
        fs.put(source / "mods/AlreadyInTarget-1.0.jar", "older")
        fs.put(source / "mods/defaultserverlist-1.8.1.jar", "default servers")
        fs.put(target / "mods/AlreadyInTarget-2.0.jar", "newer")
        val jar = checkNotNull(
            ("UEsDBBQAAAAAAMsdel01rH4EFgAAABYAAAAKAAAAbWNtb2QuaW5mb1t7Im1vZGlkIjoic2FtZS1tb2QifV1QSwEC" +
                "FAMUAAAAAADLHXpdNax+BBYAAAAWAAAACgAAAAAAAAAAAAAAgAEAAAAAbWNtb2QuaW5mb1BLBQYAAAAAAQABADgAAAA+AAAAAAA=")
                .decodeBase64(),
        )
        fs.write(source / "mods/OldFilename-1.0.jar") { write(jar) }
        fs.write(target / "mods/DifferentFilename-2.0.jar") { write(jar) }
        val migration = ConfigMigration(fs, root)

        assertFalse(migration.hasTransferredMods(prepared))
        val review = migration.reviewExtraMods(prepared)
        val extra = "mods/1.7.10/MyAddon-1.0.jar"
        assertEquals(ExtraMods(true, listOf(extra)), review)
        assertFailsWith<IllegalStateException> {
            migration.transferExtraMods(prepared, review, setOf("mods/RemovedStock-1.0.jar"))
        }
        assertEquals(
            1,
            migration.transferExtraMods(prepared, review, setOf(extra)),
        )
        assertTrue(migration.hasTransferredMods(prepared))
        assertFalse(migration.review(prepared).migrationComplete)
        assertEquals("extra", fs.text(target / extra))
        assertEquals("extra", fs.text(source / extra))
        assertEquals("newer", fs.text(target / "mods/AlreadyInTarget-2.0.jar"))
        assertFalse(fs.exists(target / "mods/RemovedStock-1.0.jar"))
    }

    @Test
    fun requiresManualSelectionForALegacyInstanceAndRechecksTheTarget() = withInstances { root, prepared ->
        val source = prepared.source / ".minecraft"
        val target = prepared.candidate / ".minecraft"
        val mod = "mods/Optional-1.0.jar"
        fs.put(source / mod, "extra")
        val migration = ConfigMigration(fs, root)
        val review = migration.reviewExtraMods(prepared)
        assertEquals(ExtraMods(false, listOf(mod)), review)

        fs.put(target / "mods/Optional-2.0.jar", "target added a version")
        assertFailsWith<IllegalStateException> {
            migration.transferExtraMods(prepared, review, setOf(mod))
        }
        assertFalse(fs.exists(target / mod))
        assertFalse(migration.hasTransferredMods(prepared))
        assertEquals(0, migration.transferExtraMods(prepared, review, emptySet()))
        assertTrue(migration.hasTransferredMods(prepared))
        assertFalse(fs.exists(target / MIGRATION_MARKER))
    }

    @Test
    fun recoversAfterAProcessStopsBetweenMovingAndReplacingTheCandidateWorlds() = withInstances { home, prepared ->
        val game = prepared.candidate / ".minecraft"
        val config = game / "config/example.cfg"
        val saves = game / "saves"
        fs.put(config, "game setting")
        fs.put(saves / "Only in candidate/level.dat", "candidate world")
        val interrupted = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                if (source == saves) error("Process stopped after moving the original saves")
            }
        }
        val transaction = MigrationTransaction(interrupted, game)
        transaction.begin()
        fs.put(transaction.add("config/example.cfg"), "migrated setting")
        fs.put(transaction.add("saves") / "Player world/level.dat", "migrated world")
        assertFailsWith<IllegalStateException> { transaction.commit(MIGRATION_MARKER) }
        assertFalse(fs.exists(saves))
        assertEquals("migrated setting", fs.text(config))

        ConfigMigration(fs, home).recover(prepared)

        assertEquals("game setting", fs.text(config))
        assertEquals("candidate world", fs.text(saves / "Only in candidate/level.dat"))
        assertFalse(fs.exists(saves / "Player world"))
        assertFalse(fs.exists(game / MIGRATION_WORK))
        assertFalse(fs.exists(game / MIGRATION_MARKER))
    }

    @Test
    fun ignoresLineEndingDifferencesAndWritesSelectedLinesInTheTargetFormat() {
        val identical = List(2_000) { "value$it=current" }
        val lf = identical.joinToString("\n", postfix = "\n")
        val crlf = identical.joinToString("\r\n", postfix = "\r\n")
        assertTrue(checkNotNull(diffConfig("config/settings.cfg", crlf, lf, emptyMap())).edits.isEmpty())
        val previous = crlf.replace("value1000=current", "value1000=previous")
        val diff = checkNotNull(diffConfig("config/settings.cfg", previous, lf, emptyMap()))
        val change = diff.edits.single().change
        assertEquals(listOf("value1000=previous"), change.oldLines)
        assertEquals(lf.replace("value1000=current", "value1000=previous"), diff.merge(mapOf(change.id to ConfigChoice.OLD)))

        val additional = checkNotNull(diffConfig("config/settings.cfg", "one\r\ntwo", "one", emptyMap()))
        assertEquals("one\ntwo", additional.merge(mapOf(additional.edits.single().change.id to ConfigChoice.OLD)))
    }

    private fun withInstances(test: suspend (Path, PreparedInstance) -> Unit) = runBlocking {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-config-test-${Uuid.random()}"
        try {
            val source = root / "source"
            val candidate = root / "candidate"
            for (instance in listOf(source, candidate)) {
                fs.createDirectories(instance / ".minecraft")
                fs.put(instance / "instance.cfg", "name=GTNH\n")
                fs.put(instance / "mmc-pack.json", "{}")
            }
            test(root, PreparedInstance(source, candidate, "old", "new"))
        } finally {
            fs.deleteRecursively(root)
        }
    }

    private fun FileSystem.put(path: Path, text: String) {
        createDirectories(checkNotNull(path.parent))
        write(path) { writeUtf8(text) }
    }

    private fun FileSystem.text(path: Path): String = read(path) { readUtf8() }
}
