package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.config.ConfigChoice
import ru.light.gtnh.updater.core.config.MigrationResult
import ru.light.gtnh.updater.core.model.TestedInstance

internal const val KEY_CONFIG_REVIEW = "F3"
private const val KEY_ALL_NEW = "F5"
private const val KEY_APPLY = "F10"
private const val KEY_PAGE_UP = "PageUp"
private const val KEY_PAGE_DOWN = "PageDown"
private const val KEY_TAB = "Tab"
private const val FOOTER_ROWS = 2
private const val MIN_REVIEW_WIDTH = 44
private const val MIN_REVIEW_HEIGHT = 16
private const val SEARCH_PROMPT = " File search > "
private const val SEARCH_CURSOR = "_"
private const val MAX_QUERY_LENGTH = 120

internal fun reviewConfigs(
    terminal: Terminal,
    input: TerminalInput,
    updater: Updater,
    tested: TestedInstance,
): UpdateFlowExit {
    val scan = BackgroundOperation(Dispatchers.IO) { updater.reviewConfigs(tested) }
    var apply: BackgroundOperation<MigrationResult>? = null
    var fileCursor = 0
    var changeCursor = 0
    var focusFiles = true
    var scroll = ReviewScroll()
    var query = ""
    var allFiles = emptyList<ReviewFile>()
    var matchingFiles: List<ReviewFile>? = null
    var initialized = false
    val choices = mutableMapOf<String, ConfigChoice>()
    var exitRequested = false
    var previousFrame = emptyList<TerminalRow>()
    var previousControls = ""
    var previousSize = terminal.size

    try {
        while (true) {
            val scanned = scan.outcome()
            val review = scanned?.getOrNull()
            val applied = apply?.outcome()
            if (exitRequested && applied != null) return UpdateFlowExit.QUIT
            val allChanges = review?.changes.orEmpty()
            if (!initialized && review != null) {
                allFiles = review.filesForDisplay()
                initialized = true
            }
            val files = matchingFiles ?: allFiles
            val changes = files.getOrNull(fileCursor)?.changes.orEmpty()
            val focused = changes.getOrNull(changeCursor)
            val size = terminal.updateSize()
            val reviewTooSmall = allChanges.isNotEmpty() &&
                (size.width < MIN_REVIEW_WIDTH || size.height < MIN_REVIEW_HEIGHT)
            val rows = mutableListOf<TerminalRow>()
            fun row(text: String, style: String = "") {
                rows += TerminalRow(text to style)
            }
            row(" GTNH UPDATER  /  Migrate data and configs", HIGHLIGHT)
            val controls: String
            when {
                scanned == null -> {
                    row(" Comparing configs...", SECTION_STYLE)
                    controls = "Esc: Back"
                }
                scanned.isFailure -> {
                    row(" Could not compare configs: ${scanned.exceptionOrNull()?.message.orEmpty().terminalText()}", "")
                    controls = "Esc: Back"
                }
                review?.migrationComplete == true -> {
                    row(" Migration is complete for this test instance.", SECTION_STYLE)
                    row(" Press Enter to continue to activation or Esc for the main menu.")
                    controls = "Enter: Continue | Esc: Main menu"
                }
                apply != null -> {
                    row(when {
                        applied == null -> " Migrating player data and configs..."
                        applied.isSuccess -> applied.getOrThrow().let {
                            " Migration complete. Copied ${it.playerEntries} data entries and updated ${it.configFiles} config files."
                        }
                        else -> " Migration failed: ${applied.exceptionOrNull()?.message.orEmpty().terminalText()}"
                    }, SECTION_STYLE)
                    if (applied?.isSuccess == true) {
                        row(" Next: Enter continues to activation. From the main menu, use F6 to resume.")
                        row(" The original instance is still active.", "")
                    }
                    controls = when {
                        applied == null -> ""
                        applied.isSuccess -> "Enter: Continue | Esc: Main menu"
                        else -> "Esc: Back"
                    }
                }
                reviewTooSmall -> {
                    row(" Enlarge the terminal to review or apply config changes.", SECTION_STYLE)
                    row(" Minimum size: $MIN_REVIEW_WIDTH columns by $MIN_REVIEW_HEIGHT rows.")
                    controls = "Esc: Back"
                }
                else -> {
                    val width = (size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
                    val searchWidth = (width - SEARCH_PROMPT.length - SEARCH_CURSOR.length).coerceAtLeast(1)
                    row("$SEARCH_PROMPT${query.takeLast(searchWidth)}$SEARCH_CURSOR")
                    val found = if (query.isBlank()) "${allChanges.size}" else "${files.sumOf { it.changes.size }}/${allChanges.size}"
                    row(" $found changes | ${files.size} files | Unselected changes use new values", SECTION_STYLE)
                    row(" F10 also copies ${review?.playerData?.size ?: 0} player data entries. Close both games first.")
                    if (query.isNotBlank() && files.isEmpty()) row(" No matching config files.")
                    val pane = renderReviewPane(
                        files, fileCursor, changeCursor, focusFiles, choices, review?.playerData.orEmpty(),
                        width, size.height - rows.size - FOOTER_ROWS, scroll,
                    )
                    rows += pane.rows
                    scroll = pane.scroll
                    controls = "Tab Focus | ↑↓ Move | ← Old | → New | PgUp PgDn | F5 All new | F10 Migrate | Esc"
                }
            }
            if (rows != previousFrame || controls != previousControls || size != previousSize) {
                renderScreenRows(terminal, rows, controls)
                previousFrame = rows
                previousControls = controls
                previousSize = size
            }

            val key = input.readKeyOrNull() ?: continue
            if (key.requestsExit()) {
                if (apply == null || applied != null) return UpdateFlowExit.QUIT
                exitRequested = true
                continue
            }
            if (key.ctrl || key.alt) continue
            if (applied?.isSuccess == true || review?.migrationComplete == true) {
                when (key.key) {
                    KEY_ESCAPE -> return UpdateFlowExit.MENU
                    KEY_ENTER -> return UpdateFlowExit.BACK
                }
                continue
            }
            if (key.key == KEY_ESCAPE && (apply == null || applied != null) &&
                (apply != null || query.isEmpty() || reviewTooSmall)
            ) {
                return UpdateFlowExit.BACK
            }
            if (review == null || review.migrationComplete || apply != null || reviewTooSmall) continue
            val previousQuery = query
            when (key.key) {
                KEY_ESCAPE -> query = ""
                KEY_BACKSPACE -> query = query.dropLast(1)
                KEY_TAB -> focusFiles = !focusFiles
                KEY_UP, KEY_DOWN -> {
                    val step = if (key.key == KEY_UP) -1 else 1
                    if (focusFiles) {
                        val next = (fileCursor + step).coerceIn(0, files.lastIndex.coerceAtLeast(0))
                        if (fileCursor != next) {
                            changeCursor = 0
                            scroll = scroll.copy(changes = 0)
                        }
                        fileCursor = next
                    } else {
                        changeCursor = (changeCursor + step).coerceIn(0, changes.lastIndex.coerceAtLeast(0))
                    }
                    scroll = scroll.copy(preview = 0)
                }
                KEY_LEFT, KEY_RIGHT -> focused?.let {
                    choices[it.id] = if (key.key == KEY_LEFT) ConfigChoice.OLD else ConfigChoice.NEW
                }
                KEY_PAGE_UP -> scroll = scroll.copy(preview = scroll.preview - (size.height / 2).coerceAtLeast(1))
                KEY_PAGE_DOWN -> scroll = scroll.copy(preview = scroll.preview + (size.height / 2).coerceAtLeast(1))
                KEY_ALL_NEW -> allChanges.forEach { choices[it.id] = ConfigChoice.NEW }
                KEY_APPLY -> {
                    val selected = allChanges.mapNotNull { change ->
                        (choices[change.id] ?: change.remembered)?.let { change.id to it }
                    }.toMap()
                    apply = BackgroundOperation(Dispatchers.IO) { updater.migrateInstance(tested, review, selected) }
                }
                else -> if (key.key.length == 1 && !key.key.single().isISOControl() && query.length < MAX_QUERY_LENGTH) {
                    query += key.key
                }
            }
            if (query != previousQuery) {
                matchingFiles = if (query.isBlank()) null else fuzzySearch(allFiles, query) { searchFields(it.path) }
                fileCursor = 0
                changeCursor = 0
                scroll = ReviewScroll()
            }
        }
    } finally {
        scan.cancel()
    }
}

private fun searchFields(path: String): List<String> = listOf(path.substringAfterLast('/'), path)
