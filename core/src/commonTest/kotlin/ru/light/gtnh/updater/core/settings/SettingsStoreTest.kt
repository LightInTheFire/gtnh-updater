package ru.light.gtnh.updater.core.settings

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.catalog.configureCatalogRequest
import ru.light.gtnh.updater.core.model.AppSettings

class SettingsStoreTest {
    @Test
    fun migratesExistingSettingsWithoutStartingSetupAgain() = withSettingsDirectory { directory ->
        val old = directory / "legacy"
        val current = directory / "home"
        val settings = AppSettings(TEST_TOKEN, removeDefaultServerList = true, transferExtraMods = true)
        SettingsStore(old).save(settings)

        assertEquals(settings, SettingsStore(current, legacyDirectory = old).load())
        assertEquals(settings, SettingsStore(current).load())
        assertNull(SettingsStore(old).load())
    }

    @Test
    fun restrictsAuthorizationToTheDailyApi() {
        val api = HttpRequestBuilder()
        api.configureCatalogRequest(
            "https://api.github.com/repos/GTNewHorizons/GTNH-Daily-Builds/releases?per_page=30",
            "application/json",
            TEST_TOKEN,
        )
        val public = HttpRequestBuilder()
        public.configureCatalogRequest(
            "https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases",
            "text/html",
            TEST_TOKEN,
        )
        val stable = HttpRequestBuilder()
        stable.configureCatalogRequest(
            "https://downloads.gtnewhorizons.com/versions.json",
            "application/json",
            TEST_TOKEN,
        )

        assertEquals("Bearer $TEST_TOKEN", api.headers[HttpHeaders.Authorization])
        assertNull(public.headers[HttpHeaders.Authorization])
        assertNull(stable.headers[HttpHeaders.Authorization])
    }

    private fun withSettingsDirectory(test: (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-settings-test-${Uuid.random()}"
        try {
            test(directory)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }
}

private const val TEST_TOKEN = "test-placeholder-token"
