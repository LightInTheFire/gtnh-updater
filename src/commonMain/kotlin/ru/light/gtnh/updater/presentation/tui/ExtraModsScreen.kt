package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.model.TestedInstance

internal const val KEY_EXTRA_MODS = "F2"
private const val MOD_LIST_HEADER_ROWS = 4
private const val MOD_LIST_FOOTER_ROWS = 2
private const val MAX_MOD_QUERY_LENGTH = 120

internal fun transferExtraMods(
    terminal: Terminal,
    input: TerminalInput,
    updater: Updater,
    tested: TestedInstance,
): Boolean {
    val scan = BackgroundOperation(Dispatchers.IO) { updater.reviewExtraMods(tested) }
    var copy: BackgroundOperation<Int>? = null
    var exitRequested = false
    var initialized = false
    val selected = mutableSetOf<String>()
    var query = ""
    var matches = emptyList<String>()
    var cursor = 0
    var offset = 0
    var previousRows = emptyList<Pair<String, String>>()
    var previousSize = terminal.size
    var previousFooter = ""
    try {
        while (true) {
            val scanned = scan.outcome()
            val extras = scanned?.getOrNull()
            val copied = copy?.outcome()
            if (exitRequested && copied != null) return true
            if (copied?.isSuccess == true) return false
            if (!initialized && extras != null) {
                initialized = true
                matches = extras.paths
                if (extras.hasBaseline) selected.addAll(extras.paths)
            }
            val size = terminal.updateSize()
            val page = (size.height - MOD_LIST_HEADER_ROWS - MOD_LIST_FOOTER_ROWS).coerceAtLeast(1)
            if (cursor < offset) offset = cursor
            if (cursor >= offset + page) offset = cursor - page + 1
            val rows = buildList {
                add(" GTNH UPDATER  /  Extra mods" to HIGHLIGHT)
                when {
                    scanned == null -> {
                        add(" Checking installed mods..." to SECTION_STYLE)
                        return@buildList
                    }
                    scanned.isFailure -> {
                        add(" Could not list mods: ${scanned.exceptionOrNull()?.message.orEmpty().terminalText()}" to SECTION_STYLE)
                        return@buildList
                    }
                    copy != null -> {
                        val message = if (copied == null) " Copying mods..."
                        else " Could not transfer mods: ${copied.exceptionOrNull()?.message.orEmpty().terminalText()}"
                        add(message to SECTION_STYLE)
                        return@buildList
                    }
                }
                val available = checkNotNull(extras)
                val hint = if (available.hasBaseline) "Extra mods detected from the shipped inventory."
                    else "Pack inventory unavailable. Select only mods you added."
                add(" $hint" to SECTION_STYLE)
                add(" Search > ${query.takeLast((size.width - " Search > _".length - TERMINAL_EDGE_MARGIN).coerceAtLeast(1))}_" to "")
                add(" ${selected.size} selected | ${matches.size}/${available.paths.size} matches" to SECTION_STYLE)
                if (matches.isEmpty()) add(" No matching mods." to "")
                for (index in offset until minOf(offset + page, matches.size)) {
                    val path = matches[index]
                    val mark = if (path in selected) "[x]" else "[ ]"
                    val marker = if (index == cursor) ">" else " "
                    add(" $marker $mark ${path.terminalText()}" to if (index == cursor) HIGHLIGHT else "")
                }
            }
            val footer = when {
                extras == null -> "Esc: Back"
                copy != null -> if (copied == null) "" else "Enter/Esc: Back"
                selected.isEmpty() -> "↑↓ Move | Space Toggle | Enter: Skip mods | Esc: Back"
                else -> "↑↓ Move | Space Toggle | Enter: Copy selected mods | Esc: Back"
            }
            if (rows != previousRows || size != previousSize || footer != previousFooter) {
                renderScreen(terminal, rows, footer)
                previousFooter = footer
                previousRows = rows
                previousSize = size
            }
            val key = input.readKeyOrNull() ?: continue
            if (key.requestsExit()) {
                if (copy == null || copied != null) return true
                exitRequested = true
                continue
            }
            if (key.ctrl || key.alt) continue
            if (key.key == KEY_ESCAPE && (copy == null || copied != null)) return false
            if (copy != null) {
                if (copied != null && key.key == KEY_ENTER) return false
                continue
            }
            if (extras == null) continue
            val previousQuery = query
            when (key.key) {
                KEY_ENTER -> {
                    val paths = selected.toSet()
                    copy = BackgroundOperation(Dispatchers.IO) { updater.transferExtraMods(tested, extras, paths) }
                }
                KEY_UP -> cursor = (cursor - 1).coerceAtLeast(0)
                KEY_DOWN -> cursor = (cursor + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                " ", "Space" -> matches.getOrNull(cursor)?.let { if (!selected.add(it)) selected.remove(it) }
                KEY_BACKSPACE -> query = query.dropLast(1)
                else -> if (key.key.length == 1 && !key.key.single().isISOControl() && query.length < MAX_MOD_QUERY_LENGTH) {
                    query += key.key
                }
            }
            if (previousQuery != query) {
                matches = fuzzySearch(extras.paths, query) { listOf(it) }
                cursor = 0
                offset = 0
            }
        }
    } finally {
        scan.cancel()
    }
}
