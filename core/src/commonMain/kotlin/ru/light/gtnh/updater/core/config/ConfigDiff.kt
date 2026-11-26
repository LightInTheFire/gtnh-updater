package ru.light.gtnh.updater.core.config

import okio.ByteString.Companion.encodeUtf8
import okio.Path

enum class ConfigChoice { OLD, NEW }

data class ConfigComparisonProgress(
    val checkedFiles: Int,
    val totalFiles: Int,
    val currentFile: String?,
)

class ConfigChange internal constructor(
    val id: String,
    val path: String,
    val oldLine: Int,
    val newLine: Int,
    val oldLines: List<String>,
    val newLines: List<String>,
    val remembered: ConfigChoice?,
    val jsonPointer: String? = null,
)

class ConfigReview internal constructor(
    internal val target: Path,
    internal val files: List<ConfigFile>,
    val skipped: List<String>,
    val playerData: List<String> = emptyList(),
    val migrationComplete: Boolean = false,
    val playerFolders: List<String> = emptyList(),
    val ignoredPlayerFolders: Set<String> = emptySet(),
) {
    val changes: List<ConfigChange> = files.flatMap { it.edits.map(ConfigEdit::change) }
}

internal data class ConfigFile(
    val path: String,
    val before: String?,
    val after: String?,
    val targetLines: List<String>,
    val edits: List<ConfigEdit>,
    val annotations: Map<Int, List<String>> = emptyMap(),
    val json: JsonConfig? = null,
    val expectedTarget: String? = after,
)

internal data class ConfigEdit(val change: ConfigChange, val start: Int, val end: Int, val old: List<String>)

private const val MAX_DIFF_CELLS = 2_000_000L
private val assignment = Regex("""\s*([\w.:"' -]+?)\s*=.*""")
private val createdLine = Regex("""^(?:(?:#|//|;|!|\*)\s*)?created(?:\s.*|[:=].*)?$""", RegexOption.IGNORE_CASE)

internal fun diffConfig(
    path: String,
    before: String?,
    after: String?,
    history: Map<String, ConfigChoice>,
): ConfigFile? {
    if (path.endsWith(".json", ignoreCase = true)) return diffJsonConfig(path, before, after, history)
    if (listOfNotNull(before, after).all { it.trimStart().startsWith('{') || it.trimStart().startsWith('[') }) {
        try {
            return diffJsonConfig(path, before, after, history)
        } catch (_: IllegalArgumentException) {
            // Other config formats can also begin with a bracket.
        }
    }
    val originalOld = before?.let(::lines).orEmpty()
    val originalNew = after?.let(::lines).orEmpty()
    val annotations = mutableMapOf<Int, MutableList<String>>()
    val oldIndices = originalOld.indices.filterNot { isIgnoredLine(originalOld[it]) }
    val newIndices = originalNew.indices.filterNot { isIgnoredLine(originalNew[it]) }
    val old = oldIndices.map(originalOld::get)
    val new = newIndices.map(originalNew::get)
    val oldText = old.map(::displayLine)
    val newText = new.map(::displayLine)
    var position = 0
    for (line in originalNew) {
        if (isIgnoredLine(line)) annotations.getOrPut(position, ::mutableListOf).add(line) else position++
    }
    val edits = mutableListOf<ConfigEdit>()
    val occurrences = mutableMapOf<String, Int>()
    fun edit(i: Int, j: Int, endOld: Int, endNew: Int) {
        val removed = old.subList(i, endOld)
        val added = new.subList(j, endNew)
        val previous = new.getOrNull(j - 1)
        val next = new.getOrNull(endNew)
        val signature = buildString {
            for (part in listOf(path, previous.orEmpty(), next.orEmpty(), removed.joinToString(""), added.joinToString(""))) {
                append(part.length).append(':').append(part)
            }
            append(before != null).append(after != null)
        }.encodeUtf8().sha256().hex()
        val occurrence = occurrences.getOrElse(signature) { 0 }
        occurrences[signature] = occurrence + 1
        val id = "$signature-$occurrence"
        val change = ConfigChange(
            id, path, (oldIndices.getOrNull(i) ?: originalOld.size) + 1,
            (newIndices.getOrNull(j) ?: originalNew.size) + 1,
            removed.map(::displayLine), added.map(::displayLine), history[id],
        )
        edits += ConfigEdit(change, j, endNew, removed)
    }
    if (before == null || after == null) {
        edit(0, 0, old.size, new.size)
    } else {
        var prefix = 0
        while (prefix < minOf(old.size, new.size) && oldText[prefix] == newText[prefix]) prefix++
        var endOld = old.size
        var endNew = new.size
        while (endOld > prefix && endNew > prefix && oldText[endOld - 1] == newText[endNew - 1]) {
            endOld--
            endNew--
        }
        val height = endOld - prefix
        val width = endNew - prefix
        if ((width + 1L) * (height + 1L) > MAX_DIFF_CELLS) return null
        val stride = width + 1
        val lengths = IntArray((height + 1) * stride)
        for (i in height - 1 downTo 0) {
            for (j in width - 1 downTo 0) {
                val cell = i * stride + j
                lengths[cell] = if (oldText[i + prefix] == newText[j + prefix]) {
                    1 + lengths[cell + stride + 1]
                } else {
                    maxOf(lengths[cell + stride], lengths[cell + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < height || j < width) {
            if (i < height && j < width && oldText[i + prefix] == newText[j + prefix]) {
                i++
                j++
                continue
            }
            val startOld = i
            val startNew = j
            while (i < height || j < width) {
                if (i < height && j < width && oldText[i + prefix] == newText[j + prefix]) break
                if (j == width || (i < height && lengths[(i + 1) * stride + j] >= lengths[i * stride + j + 1])) i++
                else j++
            }
            val count = i - startOld
            val separateAssignments = count == j - startNew && (0 until count).all { offset ->
                val oldKey = assignment.matchEntire(displayLine(old[prefix + startOld + offset]))?.groupValues?.get(1)
                val newKey = assignment.matchEntire(displayLine(new[prefix + startNew + offset]))?.groupValues?.get(1)
                oldKey != null && oldKey == newKey
            }
            if (separateAssignments) {
                for (offset in 0 until count) {
                    val oldIndex = prefix + startOld + offset
                    val newIndex = prefix + startNew + offset
                    edit(oldIndex, newIndex, oldIndex + 1, newIndex + 1)
                }
            } else {
                edit(prefix + startOld, prefix + startNew, prefix + i, prefix + j)
            }
        }
    }
    return ConfigFile(path, before, after, new, edits, annotations)
}

internal fun ConfigFile.merge(choices: Map<String, ConfigChoice>): String? {
    if (edits.none { choices[it.change.id] == ConfigChoice.OLD }) return after
    if (json != null) return json.merge(choices)
    if (before == null || after == null) return before?.let { text ->
        lines(text).filterNot(::isCreatedLine).joinToString("")
    }
    val targetEnding = lines(after).firstNotNullOfOrNull { line -> line.ending().takeIf(String::isNotEmpty) } ?: "\n"
    return buildString {
        fun emitMetadata(index: Int) {
            annotations[index]?.forEach { append(it) }
        }
        fun emitTarget(index: Int) {
            emitMetadata(index)
            append(targetLines[index])
        }
        var cursor = 0
        for (edit in edits) {
            for (i in cursor until edit.start) emitTarget(i)
            if (choices[edit.change.id] == ConfigChoice.OLD) {
                if (edit.old.isNotEmpty() && isNotEmpty() && last() != '\r' && last() != '\n') append(targetEnding)
                edit.old.forEachIndexed { offset, line ->
                    if (edit.start + offset < edit.end) emitMetadata(edit.start + offset)
                    append(displayLine(line))
                    val destination = (edit.start + offset).takeIf { it < edit.end }?.let(targetLines::get)
                    val last = offset == edit.old.lastIndex && edit.end == targetLines.size &&
                        annotations[targetLines.size] == null
                    if (!last || after.endsWith('\n') || after.endsWith('\r')) {
                        append(destination?.ending()?.takeIf(String::isNotEmpty) ?: targetEnding)
                    }
                }
                for (i in edit.start + edit.old.size until edit.end) emitMetadata(i)
            } else {
                for (i in edit.start until edit.end) emitTarget(i)
            }
            cursor = edit.end
        }
        for (i in cursor until targetLines.size) emitTarget(i)
        emitMetadata(targetLines.size)
    }
}

private fun lines(text: String): List<String> = buildList {
    var start = 0
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char == '\r' && text.getOrNull(index) == '\n') index++
        if (char == '\n' || char == '\r') {
            add(text.substring(start, index))
            start = index
        }
    }
    if (start < text.length) add(text.substring(start))
}
private fun displayLine(line: String): String = line.trimEnd('\r', '\n')

private fun String.ending(): String = substring(displayLine(this).length)

private fun isCreatedLine(line: String): Boolean = createdLine.matches(line.trim())

private fun isIgnoredLine(line: String): Boolean = line.trimStart().startsWith('#') || isCreatedLine(line)
