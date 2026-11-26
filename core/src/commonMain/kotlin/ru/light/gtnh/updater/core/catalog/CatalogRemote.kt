package ru.light.gtnh.updater.core.catalog

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.light.gtnh.updater.core.model.PackChannel

private const val REQUEST_TIMEOUT_MS = 7_000L
private const val CONNECT_TIMEOUT_MS = 4_000L
private const val SOCKET_TIMEOUT_MS = 5_000L
private const val PUBLIC_DAILY_TIMEOUT_MS = 12_000L
private const val API_DAILY_RELEASE_LIMIT = 30
private const val JSON_MEDIA_TYPE = "application/json"
private const val HTML_MEDIA_TYPE = "text/html"
private const val RELEASE_INDEX = "https://downloads.gtnewhorizons.com/versions.json"
private const val DAILY_INDEX = "https://api.github.com/repos/GTNewHorizons/GTNH-Daily-Builds/releases?per_page=$API_DAILY_RELEASE_LIMIT"

internal suspend fun fetchCatalogIndex(channel: PackChannel, githubToken: String?): String = HttpClient {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
}.use { client ->
    if (channel == PackChannel.DAILY) client.fetchDailyIndex(githubToken)
    else client.fetchCatalog(RELEASE_INDEX, JSON_MEDIA_TYPE)
}

private suspend fun HttpClient.fetchCatalog(
    url: String,
    accept: String,
    githubToken: String? = null,
): String = get(url) {
    configureCatalogRequest(url, accept, githubToken)
}.bodyAsText()

internal fun HttpRequestBuilder.configureCatalogRequest(url: String, accept: String, githubToken: String?) {
    header("User-Agent", "gtnh-updater")
    header("Accept", accept)
    header("Cache-Control", "no-cache")
    if (url == DAILY_INDEX && githubToken != null) bearerAuth(githubToken)
}

private suspend fun HttpClient.fetchDailyIndex(githubToken: String?): String {
    try {
        return fetchCatalog(DAILY_INDEX, JSON_MEDIA_TYPE, githubToken)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The public releases page can still respond when the API times out or is rate-limited.
    }

    return checkNotNull(withTimeoutOrNull(PUBLIC_DAILY_TIMEOUT_MS) {
        val page = fetchCatalog("$GITHUB$DAILY_RELEASES", HTML_MEDIA_TYPE)
        val releases = mutableListOf<JsonObject>()
        for (tag in publicDailyTags(page).take(PUBLIC_DAILY_RELEASE_LIMIT)) {
            val encodedTag = tag.replace("+", "%2B")
            val assets = try {
                fetchCatalog("$GITHUB$DAILY_RELEASES/expanded_assets/$encodedTag", HTML_MEDIA_TYPE)
            } catch (error: ClientRequestException) {
                if (error.response.status == HttpStatusCode.NotFound) continue
                throw error
            }
            releases += buildJsonObject {
                put(DAILY_TAG, tag)
                put(DAILY_ASSETS, buildJsonArray {
                    for (url in publicDailyArchives(assets, tag)) {
                        add(buildJsonObject {
                            put(ASSET_NAME, url.substringAfterLast('/').replace("%2B", "+", ignoreCase = true))
                            put(ASSET_DOWNLOAD_URL, url)
                        })
                    }
                })
            }
        }
        JsonArray(releases).toString()
    }) { "Public daily catalog refresh timed out." }
}
