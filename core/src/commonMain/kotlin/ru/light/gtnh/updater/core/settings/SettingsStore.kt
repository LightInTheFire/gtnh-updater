package ru.light.gtnh.updater.core.settings

import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.AppSettings
import ru.light.gtnh.updater.core.platform.appHomeDirectory
import ru.light.gtnh.updater.core.platform.environmentDirectory
import ru.light.gtnh.updater.core.platform.protectSettingsFile

private const val SETTINGS_FILENAME = "settings.json"
private const val GITHUB_TOKEN_KEY = "githubToken"
private const val REMOVE_SERVER_LIST_KEY = "removeDefaultServerList"
private const val TRANSFER_EXTRA_MODS_KEY = "transferExtraMods"
private const val MAX_SETTINGS_BYTES = 64L * 1024


internal class SettingsStore(
    private val directory: Path?,
    private val legacyDirectory: Path? = null,
) {
    constructor() : this(appHomeDirectory(), previousSettingsDirectory())

    fun load(): AppSettings? {
        val path = directory?.div(SETTINGS_FILENAME)
        path?.let { read(it) }?.let { return it }
        val legacyPath = legacyDirectory?.div(SETTINGS_FILENAME)?.takeIf { it != path } ?: return null
        val settings = read(legacyPath) ?: return null
        if (path != null) {
            runCatching {
                save(settings)
                FileSystem.SYSTEM.delete(legacyPath)
            }
        }
        return settings
    }

    private fun read(path: Path): AppSettings? {
        val fs = FileSystem.SYSTEM
        val metadata = fs.metadataOrNull(path) ?: return null
        check(metadata.isRegularFile && (metadata.size ?: Long.MAX_VALUE) <= MAX_SETTINGS_BYTES) {
            "Could not read the settings file."
        }
        return fs.read(path) { decodeSettings(readUtf8()) }
    }

    fun save(settings: AppSettings) {
        val dir = checkNotNull(directory) { "No user settings directory is available." }
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir)
        val temporary = dir / ".$SETTINGS_FILENAME-${Uuid.random()}.tmp"
        try {
            fs.write(temporary, mustCreate = true) {
                protectSettingsFile(temporary)
                writeUtf8(encodeSettings(settings))
            }
            fs.atomicMove(temporary, dir / SETTINGS_FILENAME)
        } finally {
            fs.delete(temporary)
        }
    }
}

private fun encodeSettings(settings: AppSettings): String = buildJsonObject {
    settings.githubToken?.let { put(GITHUB_TOKEN_KEY, it) }
    put(REMOVE_SERVER_LIST_KEY, settings.removeDefaultServerList)
    put(TRANSFER_EXTRA_MODS_KEY, settings.transferExtraMods)
}.toString()

private fun decodeSettings(text: String): AppSettings {
    val objectValue = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid settings file.")
    fun switch(key: String): Boolean {
        val value = objectValue[key] ?: return false
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: error("Invalid settings value for $key.")
    }

    val token = objectValue[GITHUB_TOKEN_KEY]?.let { value ->
        check(value is JsonPrimitive && value.isString) { "Invalid GitHub token setting." }
        value.content.takeIf { it.isNotBlank() }
    }
    return AppSettings(token, switch(REMOVE_SERVER_LIST_KEY), switch(TRANSFER_EXTRA_MODS_KEY))
}

private fun previousSettingsDirectory(): Path? {
    val base = environmentDirectory("APPDATA")
        ?: environmentDirectory("USERPROFILE")?.div("AppData/Roaming")
        ?: environmentDirectory("XDG_CONFIG_HOME")
        ?: environmentDirectory("HOME")?.div(".config")
    return base?.div("gtnh-updater")
}
