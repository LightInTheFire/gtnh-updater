package ru.light.gtnh.updater.core.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.model.PackVersion

internal const val PUBLIC_DAILY_RELEASE_LIMIT = 10
private const val JAVA_8 = "8"
private const val JAVA_8_LABEL = "Java $JAVA_8"
private const val RELEASE_TITLE = "title"
private const val STABLE_RELEASE_TITLE = "Stable release"
private const val BETA_RELEASE_TITLE = "Beta release"
internal const val DAILY_TAG = "tag_name"
internal const val DAILY_ASSETS = "assets"
internal const val ASSET_NAME = "name"
internal const val ASSET_DOWNLOAD_URL = "browser_download_url"
private const val RELEASE_PREFIX = "https://downloads.gtnewhorizons.com/Multi_mc_downloads/"
internal const val GITHUB = "https://github.com"
internal const val DAILY_RELEASES = "/GTNewHorizons/GTNH-Daily-Builds/releases"
private const val DAILY_PREFIX = "$GITHUB$DAILY_RELEASES/download/"

private val publicTagLink = Regex("""$DAILY_RELEASES/(?:tag|expanded_assets)/(daily-[0-9]{4}-[0-9]{2}-[0-9]{2}(?:%2B|\+)[0-9]+)["']""", RegexOption.IGNORE_CASE)
private val publicDownloadLink = Regex("""href=["']($DAILY_RELEASES/download/[A-Za-z0-9./_+%()-]+\.zip)["']""")

internal fun publicDailyTags(page: String): List<String> = publicTagLink.findAll(page)
    .map { it.groupValues[1].replace("%2B", "+", ignoreCase = true) }
    .distinct()
    .toList()

internal fun publicDailyArchives(page: String, tag: String): List<String> = publicDownloadLink.findAll(page)
    .map { it.groupValues[1] }
    .filter { path ->
        val decoded = path.replace("%2B", "+", ignoreCase = true)
        decoded.startsWith("$DAILY_RELEASES/download/$tag/") &&
            dailyArchive.matches(decoded.substringAfterLast('/'))
    }
    .map { GITHUB + it }
    .distinct()
    .toList()

internal fun parseCatalog(channel: PackChannel, body: String, status: String): ChannelCatalog {
    val json = Json.parseToJsonElement(body)
    val archives = linkedMapOf<String, List<ClientArchive>>()
    val versions = if (channel == PackChannel.DAILY) {
        require(json is JsonArray)
        json.mapNotNull { item ->
            val release = item as? JsonObject ?: return@mapNotNull null
            val version = release.text(DAILY_TAG)?.let(PackVersion::parse) ?: return@mapNotNull null
            if (version.channel != channel || release.text("draft") == "true") return@mapNotNull null
            val options = (release[DAILY_ASSETS] as? JsonArray).orEmpty().mapNotNull asset@{ entry ->
                val asset = entry as? JsonObject ?: return@asset null
                val name = asset.text(ASSET_NAME) ?: return@asset null
                val match = dailyArchive.matchEntire(name) ?: return@asset null
                val url = asset.text(ASSET_DOWNLOAD_URL) ?: return@asset null
                val releasePath = "${version.id.replace("+", "%2B")}/"
                if (!url.replace("+", "%2B").startsWith(DAILY_PREFIX + releasePath)) return@asset null
                archive(match.groupValues[1], url, DAILY_PREFIX)
            }
            if (options.isEmpty()) return@mapNotNull null
            archives[version.id] = options.sortedBy { it.label == JAVA_8_LABEL }
            version
        }
    } else {
        require(json is JsonObject)
        json.mapNotNull { (id, item) ->
            val release = item as? JsonObject ?: return@mapNotNull null
            val releaseChannel = when (release.text(RELEASE_TITLE)) {
                STABLE_RELEASE_TITLE -> PackChannel.STABLE
                BETA_RELEASE_TITLE -> PackChannel.BETA
                else -> return@mapNotNull null
            }
            if (releaseChannel != channel) return@mapNotNull null
            val version = PackVersion.fromReleaseIndex(id, releaseChannel) ?: return@mapNotNull null
            val mmc = release["mmc"] as? JsonObject ?: return@mapNotNull null
            val options = listOfNotNull(
                mmc.text("java17_2XUrl")?.let { url ->
                    releaseJava.find(url)?.groupValues?.get(1)?.let { archive(it, url, RELEASE_PREFIX) }
                },
                mmc.text("java8Url")?.let { archive(JAVA_8, it, RELEASE_PREFIX) },
            )
            if (options.isEmpty()) return@mapNotNull null
            archives[version.id] = options
            version
        }
    }

    require(versions.isNotEmpty()) { "No client archives found for ${channel.label}." }
    return ChannelCatalog(versions.distinctBy { it.id }, archives, status)
}

private val dailyArchive = Regex("""GTNH-daily-[0-9-]+\+[0-9]+-mmcprism-java(8|17-[0-9]+)\.zip""", RegexOption.IGNORE_CASE)
private val releaseJava = Regex("""_Java_(17-[0-9]+)\.zip$""")
private val archiveUrl = Regex("""https://[A-Za-z0-9./_+%()-]+\.zip""")

private fun archive(java: String, url: String, prefix: String): ClientArchive? =
    if (url.startsWith(prefix) && archiveUrl.matches(url)) ClientArchive("Java $java", url) else null

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content
