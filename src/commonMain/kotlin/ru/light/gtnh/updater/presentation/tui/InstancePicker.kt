package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import okio.Path
import ru.light.gtnh.updater.core.InstanceBrowser
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.model.InstanceChoice

private const val KEY_TAB = "Tab"
private const val PICKER_HEADER_ROWS = 3
private const val PICKER_FOOTER_ROWS = 2
private const val SEARCH_PREFIX = " Search > "
private const val SEARCH_CURSOR = "_"
private const val SEARCH_DEBOUNCE_MS = 120L

private data class FolderListing(val directory: Path, val matches: List<InstanceChoice>)
private data class FolderInspection(val directory: Path, val instance: InstanceChoice?)

internal sealed interface InstanceSelection {
    data class Selected(val path: Path) : InstanceSelection
    data object Back : InstanceSelection
    data object Quit : InstanceSelection
}

internal fun pickInstance(
    terminal: Terminal,
    readKey: () -> KeyboardEvent?,
    targetVersion: String,
    finder: InstanceBrowser,
): InstanceSelection {
    var discovery: BackgroundOperation<List<InstanceChoice>>? = BackgroundOperation(Dispatchers.IO) { finder.discover() }
    var listing: BackgroundOperation<FolderListing>? = null
    var validation: BackgroundOperation<FolderInspection>? = null
    var detected = emptyList<InstanceChoice>()
    var browsing = false
    var failure: String? = null
    var directory: Path? = null
    var query = ""
    var cursor = 0
    var offset = 0
    var redraw = true
    var previousSize = 0 to 0
    var matches = detected

    fun refreshMatches(debounce: Boolean = false) {
        listing?.cancel()
        validation?.cancel()
        validation = null
        failure = null
        cursor = 0
        offset = 0
        val search = query
        val requestedDirectory = directory
        if (browsing) {
            matches = emptyList()
            listing = BackgroundOperation(Dispatchers.IO) {
                if (debounce) delay(SEARCH_DEBOUNCE_MS)
                val path = requestedDirectory ?: finder.startingDirectory()
                val choices = finder.searchFolders(path, search)
                    .map { InstanceChoice(it, it.name.ifEmpty { it.toString() }) }
                FolderListing(path, choices)
            }
        } else {
            listing = null
            matches = fuzzySearch(detected, search) { listOf(it.name, it.directory.toString(), it.launcher.orEmpty()) }
        }
    }

    try {
        while (true) {
            discovery?.outcome()?.let { outcome ->
                discovery = null
                outcome.onSuccess { detected = it }
                if (!browsing) {
                    refreshMatches()
                    if (outcome.isFailure) failure = "Could not discover instances."
                }
                redraw = true
            }
            listing?.outcome()?.let { outcome ->
                listing = null
                outcome.fold(
                    onSuccess = {
                        directory = it.directory
                        matches = it.matches
                    },
                    onFailure = { failure = "Could not read the folder." },
                )
                redraw = true
            }
            validation?.outcome()?.let { outcome ->
                validation = null
                outcome.fold(
                    onSuccess = { inspection ->
                        if (inspection.instance != null) return InstanceSelection.Selected(inspection.instance.directory)
                        directory = inspection.directory
                        query = ""
                        refreshMatches()
                    },
                    onFailure = { failure = "Could not inspect the selected folder." },
                )
                redraw = true
            }
            val size = terminal.updateSize()
            val rowsAvailable = (size.height - PICKER_HEADER_ROWS - PICKER_FOOTER_ROWS).coerceAtLeast(1)
            if (cursor < offset) offset = cursor
            if (cursor >= offset + rowsAvailable) offset = cursor - rowsAvailable + 1
            val dimensions = size.width to size.height
            val currentDirectory = directory
            if (redraw || dimensions != previousSize) {
                val searchWidth = (size.width - SEARCH_PREFIX.length - SEARCH_CURSOR.length - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
                val rows = buildList {
                    add(" GTNH UPDATER  /  Choose instance" to HIGHLIGHT)
                    val location = currentDirectory?.toString()?.terminalText() ?: if (browsing) "Browse folders" else "Detected instances"
                    add(" $location  |  Target: $targetVersion" to SECTION_STYLE)
                    add("$SEARCH_PREFIX${query.takeLast(searchWidth).terminalText()}$SEARCH_CURSOR" to "")
                    val status = when {
                        browsing && listing != null -> "Loading folders..."
                        !browsing && discovery != null -> "Finding instances..."
                        validation != null -> "Checking folder..."
                        failure != null -> failure
                        matches.isNotEmpty() -> null
                        !browsing && query.isEmpty() -> "No instances detected."
                        else -> "No matches."
                    }
                    if (status != null) add(" $status" to "")
                    for (index in offset until minOf(offset + rowsAvailable, matches.size)) {
                        val choice = matches[index]
                        val details = if (choice.launcher == null) "" else "  [${choice.launcher}]  ${choice.directory}"
                        val marker = if (cursor == index) ">" else " "
                        add(" $marker  ${choice.name.terminalText()}${details.terminalText()}" to
                            if (cursor == index) HIGHLIGHT else "")
                    }
                }
                val footer = if (!browsing) {
                    "Up/Down: Move   Enter: Select   Tab: Browse folders   Esc: Back"
                } else {
                    "Enter: Open/select   Left: Parent   Tab/Esc: Instances"
                }
                renderScreen(terminal, rows, footer)
                redraw = false
                previousSize = dimensions
            }

            val key = readKey() ?: continue
            if (key.requestsExit()) return InstanceSelection.Quit
            if (key.ctrl || key.alt) continue
            when (key.key) {
                KEY_ESCAPE, KEY_LEFT, KEY_TAB -> {
                    if (!browsing && key.key != KEY_TAB) return InstanceSelection.Back
                    if (key.key == KEY_LEFT && currentDirectory?.parent != null) {
                        directory = currentDirectory.parent
                    } else {
                        browsing = !browsing
                        directory = null
                    }
                    query = ""
                    refreshMatches()
                }
                KEY_UP -> if (validation == null) cursor = (cursor - 1).coerceAtLeast(0)
                KEY_DOWN -> if (validation == null) cursor = (cursor + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                KEY_ENTER, KEY_RIGHT -> {
                    val choice = matches.getOrNull(cursor) ?: continue
                    if (!browsing) return InstanceSelection.Selected(choice.directory)
                    if (validation == null) {
                        failure = null
                        validation = BackgroundOperation(Dispatchers.IO) {
                            FolderInspection(choice.directory, finder.instance(choice.directory))
                        }
                    }
                }
                KEY_BACKSPACE -> {
                    query = query.dropLast(1)
                    refreshMatches(debounce = true)
                }
                else -> if (key.key.length == 1 && !key.key.single().isISOControl()) {
                    query += key.key
                    refreshMatches(debounce = true)
                }
            }
            redraw = true
        }
    } finally {
        discovery?.cancel()
        listing?.cancel()
        validation?.cancel()
    }
}
