package ru.light.gtnh.updater.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackVersionTest {
    @Test
    fun classifiesStableReleasesAndPrereleases() {
        assertEquals(PackChannel.STABLE, PackVersion.parse("2.9.2")?.channel)
        assertEquals(PackChannel.STABLE, PackVersion.parse("2.7.2-QF")?.channel)
        assertEquals(PackChannel.BETA, PackVersion.parse("2.9.0-beta-3")?.channel)
        val candidate = assertNotNull(PackVersion.parse("2.9.0-RC-2"))
        assertEquals(PackChannel.BETA, candidate.channel)
        assertEquals("https://www.gtnewhorizons.com/version-history/", candidate.sourceUrl)
    }

    @Test
    fun retainsTheDailyBuildNumber() {
        val version = assertNotNull(PackVersion.parse("daily-2026-11-24+855"))
        assertEquals(PackChannel.DAILY, version.channel)
        assertEquals(
            "https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases/tag/daily-2026-11-24+855",
            version.sourceUrl,
        )
    }

    @Test
    fun rejectsAmbiguousOrUnsafeIdentifiers() {
        assertNull(PackVersion.parse("latest"))
        assertNull(PackVersion.parse("daily-2026-11-24"))
        assertNull(PackVersion.parse("daily-2026-11-24+855/other"))
    }
}
