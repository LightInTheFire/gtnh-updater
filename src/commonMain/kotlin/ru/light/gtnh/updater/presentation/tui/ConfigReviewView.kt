package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import ru.light.gtnh.updater.core.config.ConfigChange
import ru.light.gtnh.updater.core.config.ConfigChoice
import ru.light.gtnh.updater.core.config.ConfigReview

private const val MIN_SPLIT_WIDTH = 84
private const val MIN_FILE_WIDTH = 28
private const val MAX_FILE_WIDTH = 58
private const val SEPARATOR_WIDTH = 3
private const val MAX_STACKED_FILE_ROWS = 4
private const val MAX_CHANGE_ROWS = 4
private const val OLD_COLOR = "31"
private const val NEW_COLOR = "32"

internal data class ReviewFile(val path: String, val label: String, val changes: List<ConfigChange>)

internal fun ConfigReview.filesForDisplay(): List<ReviewFile> {
    val changesByPath = changes.groupBy(ConfigChange::path)
    val paths = changesByPath.keys.sorted()
    val byFilename = paths.groupBy { it.substringAfterLast('/').lowercase() }
    return paths.map { path ->
        val filename = path.substringAfterLast('/')
        val related = byFilename[filename.lowercase()].orEmpty()
        val parent = if (related.size > 1) path.substringBeforeLast('/', "") else ""
        val label = if (parent.isEmpty()) filename else "$filename / $parent"
        ReviewFile(path, label, changesByPath.getValue(path))
    }
}

internal data class ReviewScroll(val files: Int = 0, val changes: Int = 0, val preview: Int = 0)
internal data class ReviewPane(val rows: List<TerminalRow>, val scroll: ReviewScroll)

internal fun renderReviewPane(
    terminal: Terminal,
    files: List<ReviewFile>,
    fileCursor: Int,
    changeCursor: Int,
    focusFiles: Boolean,
    choices: Map<String, ConfigChoice>,
    playerData: List<String>,
    width: Int,
    height: Int,
    scroll: ReviewScroll,
): ReviewPane {
    if (height <= 0) return ReviewPane(emptyList(), scroll)
    val split = width >= MIN_SPLIT_WIDTH
    val leftWidth = (width * 2 / 5).coerceIn(MIN_FILE_WIDTH, MAX_FILE_WIDTH)
    val rightWidth = if (split) width - leftWidth - SEPARATOR_WIDTH else width
    val selected = files.getOrNull(fileCursor)
    val fileHeight = if (split) height else (height / 3 + 1).coerceAtMost(MAX_STACKED_FILE_ROWS)
    val room = (fileHeight - 1).coerceAtLeast(0)
    val firstFile = visibleOffset(scroll.files, fileCursor, room, files.size)
    val lastFile = minOf(firstFile + room, files.size)
    val fileWidth = if (split) leftWidth else width
    val fileRows = buildList {
        val title = if (focusFiles) " FILES [active]" else " FILES"
        val range = if (lastFile > firstFile) "  ${firstFile + 1}-$lastFile/${files.size}" else ""
        add("$title$range" to SECTION_STYLE)
        for (index in firstFile until lastFile) {
            val file = files[index]
            val marker = if (index == fileCursor) ">" else " "
            val label = " [${file.changes.size}]"
            val nameWidth = (fileWidth - marker.length - label.length - 2).coerceAtLeast(1)
            val name = file.label.terminalText().shortenFilename(nameWidth)
            val style = when {
                index != fileCursor -> ""
                focusFiles -> HIGHLIGHT
                else -> SECTION_STYLE
            }
            add(" $marker ${name.padEnd(nameWidth)}$label" to style)
        }
    }
    val rightHeight = if (split) height else height - fileRows.size
    val changes = selected?.changes.orEmpty()
    val focus = changes.getOrNull(changeCursor)
    val detailRows = mutableListOf((if (focusFiles) " CHANGES" else " CHANGES [active]") to SECTION_STYLE)
    if (selected != null) {
        val pathWidth = (rightWidth - 1).coerceAtLeast(1)
        val path = selected.path.terminalText().shortenPath(pathWidth * 2)
        terminal.wrapReviewLine(path, pathWidth).forEach { detailRows += " $it" to "" }
    }
    val changeCount = minOf(MAX_CHANGE_ROWS, (rightHeight - detailRows.size - 2).coerceAtLeast(0), changes.size)
    val firstChange = visibleOffset(scroll.changes, changeCursor, changeCount, changes.size)
    for (index in firstChange until minOf(firstChange + changeCount, changes.size)) {
        val change = changes[index]
        val choice = choices[change.id] ?: change.remembered
        val label = when {
            change.id in choices -> choice?.name.orEmpty()
            choice != null -> "${choice.name} (saved)"
            else -> "NEW (default)"
        }
        val marker = if (index == changeCursor) ">" else " "
        val location = change.jsonPointer?.terminalText() ?: "Line ${change.newLine}"
        val style = when {
            index != changeCursor -> ""
            focusFiles -> SECTION_STYLE
            else -> HIGHLIGHT
        }
        detailRows += " $marker [$label] $location" to style
    }
    val previewHeight = (rightHeight - detailRows.size).coerceAtLeast(0)
    val preview = buildList {
        if (focus != null) {
            val location = focus.jsonPointer?.let { " JSON ${it.terminalText()}" }
                ?: " Old line ${focus.oldLine} | New line ${focus.newLine}"
            add(location to SECTION_STYLE)
            val wrapWidth = (rightWidth - " - ".length).coerceAtLeast(1)
            for (line in focus.oldLines.ifEmpty { listOf("(no lines)") }) {
                for (part in terminal.wrapReviewLine(line.replace("\t", "    ").terminalText(), wrapWidth)) {
                    add(" - $part" to OLD_COLOR)
                }
            }
            for (line in focus.newLines.ifEmpty { listOf("(no lines)") }) {
                for (part in terminal.wrapReviewLine(line.replace("\t", "    ").terminalText(), wrapWidth)) {
                    add(" + $part" to NEW_COLOR)
                }
            }
        }
        if (playerData.isNotEmpty()) {
            add(" Player data to copy" to SECTION_STYLE)
            playerData.forEach { add("   $it" to "") }
        }
    }
    val offset = scroll.preview.coerceIn(0, (preview.size - previewHeight).coerceAtLeast(0))
    detailRows += preview.drop(offset).take(previewHeight)
    return ReviewPane(
        if (split) {
            List(minOf(height, maxOf(fileRows.size, detailRows.size))) { index ->
                TerminalRow(
                    fileRows.getOrNull(index) ?: ("" to ""),
                    detailRows.getOrNull(index) ?: ("" to ""),
                    leftWidth,
                )
            }
        } else {
            (fileRows + detailRows).take(height).map { TerminalRow(it) }
        },
        ReviewScroll(firstFile, firstChange, offset),
    )
}

private fun String.shortenPath(width: Int): String = if (length <= width) this else "…${takeLast(width - 1)}"

private fun String.shortenFilename(width: Int): String {
    if (length <= width) return this
    val filename = substringBefore(" / ")
    val remaining = width - filename.length - " / ".length
    if (remaining > 0) return "$filename / ${substringAfter(" / ").shortenPath(remaining)}"
    val tail = (width - 1) / 3
    return "${filename.take(width - tail - 1)}…${filename.takeLast(tail)}"
}

private fun visibleOffset(offset: Int, cursor: Int, height: Int, items: Int): Int {
    if (height == 0) return 0
    val start = offset.coerceIn(0, (items - height).coerceAtLeast(0))
    return when {
        cursor < start -> cursor
        cursor >= start + height -> cursor - height + 1
        else -> start
    }
}

internal fun Terminal.wrapReviewLine(text: String, width: Int): List<String> = buildList {
    var offset = 0
    while (offset < text.length) {
        val window = text.substring(offset, minOf(text.length, offset + width))
        val (fitted) = fitTerminalCells(window, width)
        if (fitted.isEmpty()) {
            add("…")
            offset += if (text[offset].isHighSurrogate() && text.getOrNull(offset + 1)?.isLowSurrogate() == true) 2 else 1
        } else {
            add(fitted)
            offset += fitted.length
        }
    }
    if (isEmpty()) add("")
}
