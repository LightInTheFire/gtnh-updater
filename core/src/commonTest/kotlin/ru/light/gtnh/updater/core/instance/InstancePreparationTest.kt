package ru.light.gtnh.updater.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.PreparationProgress

class InstancePreparationTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun preparesASeparateInstanceFromARootLayout() = withInstance(ROOT_LAYOUT) { source, archive ->
        val progress = mutableListOf<PreparationProgress>()
        val result = InstancePreparer().prepare(source, archive, onProgress = progress::add)
        assertEquals(fs.canonicalize(source), result.source)
        assertEquals("2.9.1", result.currentVersion)
        assertEquals("2.9.2", result.targetVersion)
        assertEquals("new mod", fs.read(result.candidate / ".minecraft/mods/new.jar") { readUtf8() })
        assertEquals("saved world", fs.read(source / ".minecraft/saves/world.dat") { readUtf8() })
        assertFalse(fs.exists(result.candidate / ".minecraft/saves/world.dat"))
        assertTrue(fs.exists(result.candidate.parent!! / "prepared-update.json"))
        assertEquals(progress.last().totalEntries, progress.last().extractedEntries)
    }

    @Test
    fun acceptsAnArchiveWithASingleWrapperDirectory() = withInstance(WRAPPED_LAYOUT) { source, archive ->
        val result = InstancePreparer().prepare(source, archive)
        assertTrue(fs.exists(result.candidate / "minecraft/mods/new.jar"))
        assertEquals("2.9.2", result.targetVersion)
    }

    @Test
    fun rejectsUnsafeArchivePathsAndRemovesTheCandidate() = withInstance(UNSAFE_ENTRY_LAYOUT) { source, archive ->
        assertFailsWith<IllegalStateException> { InstancePreparer().prepare(source, archive) }
        assertEquals(setOf(source, archive), fs.list(source.parent!!).toSet())
        assertEquals("saved world", fs.read(source / ".minecraft/saves/world.dat") { readUtf8() })
    }

    @Test
    fun cancellationRemovesTheCandidateWithoutChangingTheInstance() = withInstance(ROOT_LAYOUT) { source, archive ->
        assertFailsWith<CancellationException> {
            InstancePreparer().prepare(source, archive) {
                if (it.extractedEntries > 0) throw CancellationException()
            }
        }
        assertEquals(setOf(source, archive), fs.list(source.parent!!).toSet())
        assertEquals("saved world", fs.read(source / ".minecraft/saves/world.dat") { readUtf8() })
    }

    @Test
    fun installsTwoCleanCopiesWithoutChangingTheInstalledInstance() = withInstance(WRAPPED_LAYOUT) { source, archive ->
        val parent = checkNotNull(source.parent)
        val preparer = InstancePreparer()
        val first = preparer.install(archive, parent, "2.9.2", removeDefaultServerList = false)
        val second = preparer.install(archive, parent, "2.9.2", removeDefaultServerList = false)
        assertEquals("GTNH 2.9.2", first.name)
        assertEquals("GTNH 2.9.2 (2)", second.name)
        assertTrue(first.directory != second.directory)
        for (installed in listOf(first, second)) {
            assertTrue(fs.exists(installed.directory / "minecraft/mods/new.jar"))
            assertTrue(fs.exists(installed.directory / ".gtnh-shipped-mods.json"))
            assertFalse(fs.exists(installed.directory / "minecraft/saves/world.dat"))
            assertFalse(fs.exists(installed.directory / PREPARATION_MANIFEST))
        }
        assertEquals("saved world", fs.read(source / ".minecraft/saves/world.dat") { readUtf8() })
        assertEquals(setOf(source, archive, first.directory, second.directory), fs.list(parent).toSet())
    }

    @Test
    fun cancelsAStandaloneInstallationWithoutLeavingAPartialInstance() = withInstance(ROOT_LAYOUT) { source, archive ->
        val parent = checkNotNull(source.parent)
        assertFailsWith<CancellationException> {
            InstancePreparer().install(archive, parent, "2.9.2", removeDefaultServerList = false) {
                if (it.extractedEntries > 0) throw CancellationException()
            }
        }
        assertEquals(setOf(source, archive), fs.list(parent).toSet())
        assertFailsWith<IllegalArgumentException> {
            InstancePreparer().install(archive, source, "2.9.2", removeDefaultServerList = false)
        }
    }

    private fun withInstance(fixture: String, test: suspend (Path, Path) -> Unit) {
        val workspace = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-preparation-test-${Uuid.random()}"
        try {
            val source = workspace / "Installed GTNH"
            val game = source / ".minecraft"
            fs.createDirectories(game / "config/GTNewHorizons")
            fs.createDirectories(game / "saves")
            fs.write(source / "instance.cfg") { writeUtf8("name=Installed GTNH") }
            fs.write(source / "mmc-pack.json") { writeUtf8("{}") }
            fs.write(game / "config/GTNewHorizons/dreamcraft.cfg") { writeUtf8("S:ModPackVersion=2.9.1") }
            fs.write(game / "saves/world.dat") { writeUtf8("saved world") }
            val archive = workspace / "client.zip"
            fs.write(archive) { write(checkNotNull(fixture.decodeBase64())) }
            runBlocking { test(source, archive) }
        } finally {
            fs.deleteRecursively(workspace)
        }
    }
}

private const val ROOT_LAYOUT =
    "UEsDBBQAAAAIALuReV2/FTkREwAAABEAAAAMAAAAaW5zdGFuY2UuY2Zny0vMTbV1D/HzUChJLEpPLeECAFBLAwQUAAAACAC7" +
    "kXldRNLiAxUAAAATAAAADQAAAG1tYy1wYWNrLmpzb26rVkrLL8pNLAlLLSrOzM9TsjKsBQBQSwMEFAAAAAgAu5F5XZRvqR0J" +
    "AAAABwAAABcAAAAubWluZWNyYWZ0L21vZHMvbmV3LmphcstLLVfIzU8BAFBLAwQUAAAACAC7kXldXIiokxkAAAAXAAAALgAA" +
    "AC5taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpvbnMvZHJlYW1jcmFmdC5jZmcLtvLNTwlITM4OSy0qzszPszXSs9Qz4gIA" +
    "UEsBAhQDFAAAAAgAu5F5Xb8VORETAAAAEQAAAAwAAAAAAAAAAAAAAIABAAAAAGluc3RhbmNlLmNmZ1BLAQIUAxQAAAAIALuR" +
    "eV1E0uIDFQAAABMAAAANAAAAAAAAAAAAAACAAT0AAABtbWMtcGFjay5qc29uUEsBAhQDFAAAAAgAu5F5XZRvqR0JAAAABwAA" +
    "ABcAAAAAAAAAAAAAAIABfQAAAC5taW5lY3JhZnQvbW9kcy9uZXcuamFyUEsBAhQDFAAAAAgAu5F5XVyIqJMZAAAAFwAAAC4A" +
    "AAAAAAAAAAAAAIABuwAAAC5taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpvbnMvZHJlYW1jcmFmdC5jZmdQSwUGAAAAAAQA" +
    "BAAWAQAAIAEAAAAA"

private const val WRAPPED_LAYOUT =
    "UEsDBBQAAAAIALuReV2/FTkREwAAABEAAAAXAAAAR1ROSCAyLjkuMi9pbnN0YW5jZS5jZmfLS8xNtXUP8fNQKEksSk8t4QIA" +
    "UEsDBBQAAAAIALuReV1E0uIDFQAAABMAAAAYAAAAR1ROSCAyLjkuMi9tbWMtcGFjay5qc29uq1ZKyy/KTSwJSy0qzszPU7Iy" +
    "rAUAUEsDBBQAAAAIALuReV2Ub6kdCQAAAAcAAAAhAAAAR1ROSCAyLjkuMi9taW5lY3JhZnQvbW9kcy9uZXcuamFyy0stV8jN" +
    "TwEAUEsDBBQAAAAIALuReV1ciKiTGQAAABcAAAA4AAAAR1ROSCAyLjkuMi9taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpv" +
    "bnMvZHJlYW1jcmFmdC5jZmcLtvLNTwlITM4OSy0qzszPszXSs9Qz4gIAUEsBAhQDFAAAAAgAu5F5Xb8VORETAAAAEQAAABcA" +
    "AAAAAAAAAAAAAIABAAAAAEdUTkggMi45LjIvaW5zdGFuY2UuY2ZnUEsBAhQDFAAAAAgAu5F5XUTS4gMVAAAAEwAAABgAAAAA" +
    "AAAAAAAAAIABSAAAAEdUTkggMi45LjIvbW1jLXBhY2suanNvblBLAQIUAxQAAAAIALuReV2Ub6kdCQAAAAcAAAAhAAAAAAAA" +
    "AAAAAACAAZMAAABHVE5IIDIuOS4yL21pbmVjcmFmdC9tb2RzL25ldy5qYXJQSwECFAMUAAAACAC7kXldXIiokxkAAAAXAAAA" +
    "OAAAAAAAAAAAAAAAgAHbAAAAR1ROSCAyLjkuMi9taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpvbnMvZHJlYW1jcmFmdC5j" +
    "ZmdQSwUGAAAAAAQABABAAQAASgEAAAAA"

private const val UNSAFE_ENTRY_LAYOUT =
    "UEsDBBQAAAAIALuReV2/FTkREwAAABEAAAAMAAAAaW5zdGFuY2UuY2Zny0vMTbV1D/HzUChJLEpPLeECAFBLAwQUAAAACAC7" +
    "kXldRNLiAxUAAAATAAAADQAAAG1tYy1wYWNrLmpzb26rVkrLL8pNLAlLLSrOzM9TsjKsBQBQSwMEFAAAAAgAu5F5XZRvqR0J" +
    "AAAABwAAABcAAAAubWluZWNyYWZ0L21vZHMvbmV3LmphcstLLVfIzU8BAFBLAwQUAAAACAC7kXldXIiokxkAAAAXAAAALgAA" +
    "AC5taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpvbnMvZHJlYW1jcmFmdC5jZmcLtvLNTwlITM4OSy0qzszPszXSs9Qz4gIA" +
    "UEsDBBQAAAAIALuReV37OSuCBQAAAAMAAAAcAAAALm1pbmVjcmFmdC91bnN1cHBvcnRlZDplbnRyeUtKTAEAUEsBAhQDFAAA" +
    "AAgAu5F5Xb8VORETAAAAEQAAAAwAAAAAAAAAAAAAAIABAAAAAGluc3RhbmNlLmNmZ1BLAQIUAxQAAAAIALuReV1E0uIDFQAA" +
    "ABMAAAANAAAAAAAAAAAAAACAAT0AAABtbWMtcGFjay5qc29uUEsBAhQDFAAAAAgAu5F5XZRvqR0JAAAABwAAABcAAAAAAAAA" +
    "AAAAAIABfQAAAC5taW5lY3JhZnQvbW9kcy9uZXcuamFyUEsBAhQDFAAAAAgAu5F5XVyIqJMZAAAAFwAAAC4AAAAAAAAAAAAA" +
    "AIABuwAAAC5taW5lY3JhZnQvY29uZmlnL0dUTmV3SG9yaXpvbnMvZHJlYW1jcmFmdC5jZmdQSwECFAMUAAAACAC7kXld+zkr" +
    "ggUAAAADAAAAHAAAAAAAAAAAAAAAgAEgAQAALm1pbmVjcmFmdC91bnN1cHBvcnRlZDplbnRyeVBLBQYAAAAABQAFAGABAABf" +
    "AQAAAAA="
