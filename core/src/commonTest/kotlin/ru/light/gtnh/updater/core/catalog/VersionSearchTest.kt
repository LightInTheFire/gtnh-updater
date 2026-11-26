package ru.light.gtnh.updater.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.light.gtnh.updater.core.model.PackVersion

class VersionSearchTest {
    @Test
    fun ranksExactAndContiguousMatchesAboveSubsequences() {
        val versions = listOf("2.9.0-RC-2", "2.9.2", "2.9.20").map { requireNotNull(PackVersion.parse(it)) }

        assertEquals(
            listOf("2.9.2", "2.9.20", "2.9.0-RC-2"),
            searchVersions(versions, "2.9.2").map { it.id },
        )
    }

    @Test
    fun findsPartialVersionsAndChannelTerms() {
        val versions = listOf("2.9.2", "2.9.1", "2.9.0-RC-2", "daily-2026-11-24+855")
            .map { requireNotNull(PackVersion.parse(it)) }
        assertEquals("2.9.2", searchVersions(versions, "292 stable").first().id)
        assertEquals("2.9.0-RC-2", searchVersions(versions, "RC2").first().id)
        assertEquals("daily-2026-11-24+855", searchVersions(versions, "daily 855").single().id)
        assertTrue(searchVersions(versions, "no such version").isEmpty())
    }
}
