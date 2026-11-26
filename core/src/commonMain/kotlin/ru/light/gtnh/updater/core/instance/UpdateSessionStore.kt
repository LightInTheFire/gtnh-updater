package ru.light.gtnh.updater.core.instance

import kotlin.uuid.Uuid
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage
import ru.light.gtnh.updater.core.platform.appHomeDirectory

private const val SESSION_FILE = "active-update.json"

internal class UpdateSessionStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val home: Path? = appHomeDirectory(),
    private val filename: String = SESSION_FILE,
) {
    fun load(validateInstances: Boolean = true): UpdateSession? {
        val file = home?.div(filename) ?: return null
        if (!fs.exists(file)) return null
        val contents = fs.read(file) { readUtf8() }
        val data = try {
            Json.parseToJsonElement(contents) as? JsonObject
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid saved update record ($filename).", error)
        }
        requireNotNull(data) { "Invalid saved update record ($filename)." }
        fun text(key: String): String? = (data[key] as? JsonPrimitive)?.content
        val source = checkNotNull(text("source")).toPath()
        val candidate = checkNotNull(text("candidate")).toPath()
        check(source.isAbsolute && candidate.isAbsolute && source != candidate) { "Invalid saved instance paths." }
        if (validateInstances && (instanceGameDirectory(fs, source) == null || instanceGameDirectory(fs, candidate) == null)) return null
        val stage = UpdateStage.valueOf(checkNotNull(text("stage")))
        val baseline = (data["shippedConfigs"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet()
        val prepared = PreparedInstance(source, candidate, text("currentVersion"), text("targetVersion"), baseline)
        return UpdateSession(prepared, stage, text("displaced")?.toPath())
    }

    fun clear() {
        home?.div(filename)?.let { fs.delete(it) }
    }

    fun save(session: UpdateSession) {
        val dir = checkNotNull(home) { "The updater home directory is unavailable." }
        val prepared = session.prepared
        val data = buildJsonObject {
            put("source", prepared.source.toString())
            put("candidate", prepared.candidate.toString())
            put("stage", session.stage.name)
            session.displaced?.let { put("displaced", it.toString()) }
            prepared.currentVersion?.let { put("currentVersion", it) }
            prepared.targetVersion?.let { put("targetVersion", it) }
            prepared.shippedConfigs?.let { put("shippedConfigs", JsonArray(it.sorted().map(::JsonPrimitive))) }
        }
        fs.createDirectories(dir)
        val pending = dir / ".$filename-${Uuid.random()}.tmp"
        try {
            fs.write(pending, mustCreate = true) { writeUtf8(data.toString()) }
            fs.atomicMove(pending, dir / filename)
        } finally {
            fs.delete(pending)
        }
    }
}
