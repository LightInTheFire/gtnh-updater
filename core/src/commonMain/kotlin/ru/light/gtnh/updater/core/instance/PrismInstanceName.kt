package ru.light.gtnh.updater.core.instance

import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

private const val NAME_RECORD = ".gtnh-updater-prism-name.json"
private const val MAX_NAME_FILE_BYTES = 1024L * 1024
private val prismName = Regex("""(?m)^name=([^\r\n]*)""")

internal class PrismInstanceName(private val fs: FileSystem) {
    fun get(instance: Path): String? = runCatching {
        prismName.find(read(instance / INSTANCE_CONFIG))?.groupValues?.get(1)
    }.getOrNull()

    fun set(instance: Path, name: String) {
        val config = instance / INSTANCE_CONFIG
        val current = read(config)
        val updated = withName(current, name)
        if (updated != current) write(config, updated)
    }

    fun label(instance: Path, role: String, version: String?) {
        val config = instance / INSTANCE_CONFIG
        val savedName = instance / NAME_RECORD
        val current = read(config)
        val original = if (fs.exists(savedName)) readOriginal(savedName) else {
            prismName.find(current)?.groupValues?.get(1).also { name ->
                write(savedName, (name?.let(::JsonPrimitive) ?: JsonNull).toString())
            }
        }
        val suffix = version?.filterNot(Char::isISOControl)?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val name = "[$role$suffix] ${original?.takeIf { it.isNotBlank() } ?: "GT New Horizons"}"
        val updated = withName(current, name)
        if (updated != current) write(config, updated)
    }

    fun restore(instance: Path) {
        val savedName = instance / NAME_RECORD
        if (!fs.exists(savedName)) return
        val config = instance / INSTANCE_CONFIG
        val current = read(config)
        val updated = withName(current, readOriginal(savedName))
        if (updated != current) write(config, updated)
        fs.delete(savedName)
    }

    private fun readOriginal(path: Path): String? {
        val value = Json.parseToJsonElement(read(path))
        return if (value == JsonNull) null else (value as JsonPrimitive).content
    }

    private fun withName(text: String, name: String?): String {
        val row = prismName.find(text)
        if (row != null) {
            if (name != null) return text.replaceRange(row.range, "name=$name")
            val end = row.range.last + 1
            val newline = when {
                text.startsWith("\r\n", end) -> 2
                text.getOrNull(end) == '\n' || text.getOrNull(end) == '\r' -> 1
                else -> 0
            }
            return text.removeRange(row.range.first, end + newline)
        }
        if (name == null) return text
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val separator = if (text.isEmpty() || text.endsWith('\n') || text.endsWith('\r')) "" else newline
        return "$text${separator}name=$name$newline"
    }

    private fun read(path: Path): String {
        val metadata = fs.metadata(path)
        check(metadata.isRegularFile && metadata.symlinkTarget == null &&
            (metadata.size ?: Long.MAX_VALUE) <= MAX_NAME_FILE_BYTES
        ) { "Invalid Prism instance name file." }
        return fs.read(path) { readUtf8() }
    }

    private fun write(path: Path, contents: String) {
        val pending = checkNotNull(path.parent) / ".gtnh-prism-name-${Uuid.random()}.tmp"
        try {
            fs.write(pending, mustCreate = true) { writeUtf8(contents) }
            fs.atomicMove(pending, path)
        } finally {
            fs.delete(pending)
        }
    }
}
