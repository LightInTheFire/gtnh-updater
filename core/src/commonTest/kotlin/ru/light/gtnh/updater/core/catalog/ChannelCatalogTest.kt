package ru.light.gtnh.updater.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import okio.FileSystem
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.settings.SettingsStore

class ChannelCatalogTest {
    @Test
    fun migratesTheOldIndexAndKeepsAnExistingNewOne() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-index-migration-${Uuid.random()}"
        val previous = root / "previous"
        val current = root / "home"
        fun release(version: String, title: String): String =
            """{"$version":{"title":"$title","mmc":{"java8Url":"https://downloads.gtnewhorizons.com/Multi_mc_downloads/$version.zip"}}}"""

        try {
            fs.createDirectories(previous)
            fs.createDirectories(current)
            val stable = previous / "stable.json"
            val beta = current / "beta.json"
            val settings = current / "settings.json"
            fs.write(stable) { writeUtf8(release("2.9.2", "Stable release")) }
            fs.write(previous / "beta.json") { writeUtf8(release("2.9.0-RC-1", "Beta release")) }
            fs.write(beta) { writeUtf8(release("2.9.0-RC-2", "Beta release")) }
            fs.write(settings) { writeUtf8("""{"transferExtraMods":true}""") }

            val store = CatalogStore(current, legacyDirectory = previous)
            assertEquals(listOf("2.9.2"), store.load(PackChannel.STABLE).versions.map { it.id })
            assertTrue(fs.exists(stable))

            store.migrateLegacy()

            assertTrue(fs.exists(current / "stable.json"))
            assertFalse(fs.exists(stable))
            assertEquals(listOf("2.9.0-RC-2"), store.load(PackChannel.BETA).versions.map { it.id })
            assertTrue(SettingsStore(current).load()?.transferExtraMods == true)
        } finally {
            fs.deleteRecursively(root)
        }
    }

    @Test
    fun aFreshCatalogHasNoBundledVersionsOrArchives() {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-empty-catalog-${Uuid.random()}"
        val store = CatalogStore(directory)

        for (channel in PackChannel.entries) {
            val catalog = store.load(channel)
            assertTrue(catalog.versions.isEmpty())
            assertTrue(catalog.archives.isEmpty())
        }
    }

    @Test
    fun usesOnlyPrismArchivesFromTheSelectedReleaseChannel() {
        val body = """
            {
              "2.9.2": {
                "title": "Stable release",
                "mmc": {
                  "java8Url": "https://downloads.gtnewhorizons.com/Multi_mc_downloads/GT_New_Horizons_2.9.2_Java_8.zip",
                  "java17_2XUrl": "https://downloads.gtnewhorizons.com/Multi_mc_downloads/GT_New_Horizons_2.9.2_Java_17-26.zip"
                }
              },
              "2.9.0-RC-2": {
                "title": "Beta release",
                "mmc": {
                  "java8Url": "https://downloads.gtnewhorizons.com/Multi_mc_downloads/betas/GT_New_Horizons_2.9.0-RC-2_Java_8.zip"
                }
              },
              "2.4.1": {
                "title": "Beta release",
                "mmc": {
                  "java8Url": "https://downloads.gtnewhorizons.com/Multi_mc_downloads/betas/GT_New_Horizons_2.4.1_Java_8.zip"
                }
              }
            }
        """.trimIndent()
        val stable = parseCatalog(PackChannel.STABLE, body, "test")
        assertEquals(listOf("2.9.2"), stable.versions.map { it.id })
        assertEquals(listOf("Java 17-26", "Java 8"), stable.archives.getValue("2.9.2").map { it.label })
        val beta = parseCatalog(PackChannel.BETA, body, "test")
        assertEquals(listOf("2.9.0-RC-2", "2.4.1"), beta.versions.map { it.id })
        assertTrue(beta.versions.all { it.channel == PackChannel.BETA })
        assertEquals("Java 8", beta.archives.getValue("2.9.0-RC-2").single().label)
    }

    @Test
    fun dailyCatalogIgnoresServerAndManifestAssets() {
        val prefix = "https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases/download/daily-2026-11-24%2B855/"
        val body = """
            [{
              "tag_name": "daily-2026-11-24+855",
              "assets": [
                {"name": "GTNH-daily-2026-11-24+855-server-java8.zip", "browser_download_url": "${prefix}server.zip"},
                {"name": "gtnh-daily-2026-11-24+855-manifest.json.zip", "browser_download_url": "${prefix}manifest.zip"},
                {"name": "GTNH-daily-2026-11-24+855-mmcprism-java8.zip", "browser_download_url": "${prefix}client.zip"}
              ]
            }]
        """.trimIndent()
        val catalog = parseCatalog(PackChannel.DAILY, body, "test")
        val archive = catalog.archives.getValue(catalog.versions.single().id).single()
        assertEquals("Java 8", archive.label)
        assertTrue(archive.url.endsWith("/client.zip"))
    }

    @Test
    fun readsClientZipLinksFromPublicDailyPages() {
        val releases = "/GTNewHorizons/GTNH-Daily-Builds/releases"
        val tag = "daily-2026-11-24+855"
        val encoded = "daily-2026-11-24%2B855"
        val tags = """<a href="$releases/tag/$tag"></a><include-fragment src="$releases/expanded_assets/$encoded">"""
        assertEquals(listOf(tag), publicDailyTags(tags))

        val client = "$releases/download/$encoded/GTNH-$encoded-mmcprism-java17-26.zip"
        val server = "$releases/download/$encoded/GTNH-$encoded-server-java8.zip"
        val assets = """<a href="$client"></a><a href="$server"></a><a href="$client"></a>"""
        assertEquals(listOf("https://github.com$client"), publicDailyArchives(assets, tag))
        assertTrue(publicDailyArchives(assets, "daily-2026-11-24+854").isEmpty())
    }

    @Test
    fun rejectsEmptyOrUnusableResponses() {
        assertFailsWith<IllegalArgumentException> { parseCatalog(PackChannel.STABLE, "{}", "test") }
        assertFailsWith<IllegalArgumentException> { parseCatalog(PackChannel.DAILY, "[]", "test") }
    }
}
