package ru.light.gtnh.updater.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

private val configJson = Json { prettyPrint = true }
private val createdKeys = setOf("created", "createdat", "created_at")

internal data class JsonConfig(
    val target: JsonElement?,
    val edits: List<JsonEdit>,
) {
    fun merge(choices: Map<String, ConfigChoice>): String? {
        var result = target
        for (edit in edits) {
            if (choices[edit.change.id] == ConfigChoice.OLD) {
                result = replace(result, edit.keys, 0, edit.oldValue)
            }
        }
        return result?.let { configJson.encodeToString(JsonElement.serializer(), it) + "\n" }
    }
}

internal data class JsonEdit(val change: ConfigChange, val keys: List<String>, val oldValue: JsonElement?)

internal fun diffJsonConfig(
    path: String,
    before: String?,
    after: String?,
    history: Map<String, ConfigChoice>,
): ConfigFile? {
    val old = before?.let(configJson::parseToJsonElement)
    val new = after?.let(configJson::parseToJsonElement)
    val edits = mutableListOf<JsonEdit>()
    fun compare(keys: List<String>, previous: JsonElement?, incoming: JsonElement?) {
        if (canonical(previous) == canonical(incoming)) return
        if (previous is JsonObject && incoming is JsonObject) {
            for (key in (previous.keys + incoming.keys).sorted()) {
                if (!isCreatedKey(key)) compare(keys + key, previous[key], incoming[key])
            }
            return
        }
        val identity = JsonArray(listOf(
            JsonPrimitive(path), JsonArray(keys.map(::JsonPrimitive)),
            JsonPrimitive(canonical(previous).orEmpty()), JsonPrimitive(canonical(incoming).orEmpty()),
        )).toString()
        val id = identity.encodeUtf8().sha256().hex()
        val pointer = keys.joinToString("/", prefix = "/") { it.replace("~", "~0").replace("/", "~1") }
        val change = ConfigChange(id, path, 0, 0, preview(previous), preview(incoming), history[id], pointer)
        edits += JsonEdit(change, keys, preserveCreated(previous, incoming))
    }
    compare(emptyList(), old, new)
    return ConfigFile(
        path, before, after, emptyList(), edits.map { ConfigEdit(it.change, 0, 0, emptyList()) },
        json = JsonConfig(new, edits),
    )
}

private fun replace(node: JsonElement?, keys: List<String>, depth: Int, value: JsonElement?): JsonElement? {
    if (depth == keys.size) return value
    val fields = (node as JsonObject).toMutableMap()
    val key = keys[depth]
    val updated = replace(fields[key], keys, depth + 1, value)
    if (updated == null) fields.remove(key) else fields[key] = updated
    return JsonObject(fields)
}

private fun isCreatedKey(key: String): Boolean = key.lowercase() in createdKeys

private fun canonical(value: JsonElement?): String? = normalized(value)?.toString()

private fun normalized(value: JsonElement?): JsonElement? = when (value) {
    is JsonObject -> JsonObject(value.entries.filterNot { isCreatedKey(it.key) }.sortedBy { it.key }
        .associate { it.key to checkNotNull(normalized(it.value)) })
    is JsonArray -> JsonArray(value.map { checkNotNull(normalized(it)) })
    else -> value
}

private fun preserveCreated(previous: JsonElement?, incoming: JsonElement?): JsonElement? = when (previous) {
    is JsonObject -> {
        val target = incoming as? JsonObject
        val fields = previous.filterKeys { !isCreatedKey(it) }.mapValues { (key, value) ->
            checkNotNull(preserveCreated(value, target?.get(key)))
        }.toMutableMap()
        target?.filterKeys(::isCreatedKey)?.let(fields::putAll)
        JsonObject(fields)
    }
    is JsonArray -> JsonArray(previous.mapIndexed { index, element ->
        checkNotNull(preserveCreated(element, (incoming as? JsonArray)?.getOrNull(index)))
    })
    else -> previous
}

private fun preview(value: JsonElement?): List<String> =
    value?.let { preserveCreated(it, null) }?.let { configJson.encodeToString(JsonElement.serializer(), it).lines() }.orEmpty()
