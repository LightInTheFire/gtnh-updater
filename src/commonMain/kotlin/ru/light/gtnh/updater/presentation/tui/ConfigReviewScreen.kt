package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.config.ConfigChoice
import ru.light.gtnh.updater.core.config.ConfigComparisonProgress
import ru.light.gtnh.updater.core.config.MigrationProgress
import ru.light.gtnh.updater.core.config.MigrationResult
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage

internal const val KEY_CONFIG_REVIEW = "F3"
private const val KEY_ALL_NEW = "F5"
private const val KEY_APPLY = "F10"
private const val KEY_MINECRAFT_SETTINGS = "F5"
private const val KEY_PAGE_UP = "PageUp"
private const val KEY_PAGE_DOWN = "PageDown"
private const val KEY_TAB = "Tab"
private const val FOOTER_ROWS = 2
private const val MIN_REVIEW_WIDTH = 24
private const val MIN_REVIEW_HEIGHT = 12
private const val COMPACT_REVIEW_WIDTH = 78
private const val SEARCH_PROMPT = " File search > "
private const val SEARCH_CURSOR = "_"
private const val MAX_QUERY_LENGTH = 120
private const val MAX_PROGRESS_BAR_WIDTH = 24

internal fun reviewConfigs(
    terminal: Terminal,
    input: TerminalInput,
    updater: Updater,
    session: UpdateSession,
): UpdateFlowExit {
    val keepNewConfigs = session.stage == UpdateStage.NEW_CONFIGS_SELECTED
    val comparison = atomic<ConfigComparisonProgress?>(null)
    val scan = BackgroundOperation(Dispatchers.IO) {
        if (keepNewConfigs) updater.reviewPlayerData(session)
        else updater.reviewConfigs(checkNotNull(session.tested)) { comparison.value = it }
    }
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
    val progress = atomic<MigrationProgress?>(null)
    val ignoredFolders = mutableSetOf<String>()
    var choosingFolders = keepNewConfigs
    var includeMinecraftSettings = !keepNewConfigs
    var cancelling = false
    var folderCursor = 0
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
                ignoredFolders += review.ignoredPlayerFolders.intersect(review.playerFolders.toSet())
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
                    val current = comparison.value
                    val status = when {
                        keepNewConfigs -> " Checking player data..."
                        current == null -> " Finding config files..."
                        else -> " Comparing configs..."
                    }
                    row(status, SECTION_STYLE)
                    if (current != null) {
                        row(progressBar(current.checkedFiles, current.totalFiles, "files", size.width))
                        current.currentFile?.let { row(" ${it.terminalText()}") }
                    }
                    controls = "Esc: Back"
                }
                scanned.isFailure -> {
                    row(" Could not prepare migration: ${scanned.exceptionOrNull()?.message.orEmpty().terminalText()}", "")
                    controls = "Esc: Back"
                }
                review?.migrationComplete == true -> {
                    row(" Migration is complete for this test instance.", SECTION_STYLE)
                    row(" Press Enter to continue to activation or Esc for the main menu.")
                    controls = "Enter: Continue | Esc: Main menu"
                }
                apply != null -> {
                    row(when {
                        applied == null && cancelling -> " Cancelling migration and cleaning up..."
                        applied == null -> " Migrating player data and configs..."
                        applied.exceptionOrNull() is CancellationException -> " Migration cancelled."
                        applied.isSuccess -> applied.getOrThrow().let {
                            " Migration complete. Copied ${it.playerEntries} data entries and updated ${it.configFiles} config files."
                        }
                        else -> " Migration failed: ${applied.exceptionOrNull()?.message.orEmpty().terminalText()}"
                    }, SECTION_STYLE)
                    if (applied == null) {
                        progress.value?.let { current ->
                            row(progressBar(current.completedEntries, current.totalEntries, "steps", size.width))
                            row(current.currentEntry?.let { " Copying ${it.terminalText()}" } ?: " Applying configs and committing changes")
                            row(" Files copied: ${current.copiedFiles}")
                        }
                    }
                    if (applied?.isSuccess == true) {
                        row(" Next: Enter continues to activation. From the main menu, use F6 to resume.")
                        row(" The original instance is still active.", "")
                    }
                    controls = when {
                        applied == null -> if (cancelling) "" else "Esc: Cancel migration"
                        applied.isSuccess -> "Enter: Continue | Esc: Main menu"
                        else -> "Esc: Back"
                    }
                }
                choosingFolders && review != null -> {
                    row(" Choose which player data folders to skip.", SECTION_STYLE)
                    row(if (keepNewConfigs) " New configs and mods are kept. Close Minecraft before migrating."
                        else " Your choices are remembered. Config choices will also be applied.")
                    val settingsState = if (includeMinecraftSettings) "On" else "Off"
                    row(if (size.width < COMPACT_REVIEW_WIDTH) " F5: Settings $settingsState"
                        else " F5: Copy Minecraft settings: $settingsState")
                    val visibleRows = (size.height - rows.size - FOOTER_ROWS).coerceAtLeast(1)
                    val start = (folderCursor - visibleRows + 1).coerceAtLeast(0)
                    for ((offset, path) in review.playerFolders.drop(start).take(visibleRows).withIndex()) {
                        val focusedFolder = offset + start == folderCursor
                        val skipped = path in ignoredFolders || path.substringBefore('/') in ignoredFolders
                        val state = if (skipped) "Skip" else "Copy"
                        row(" $state  ${path.terminalText()}", if (focusedFolder) HIGHLIGHT else "")
                    }
                    if (review.playerFolders.isEmpty()) row(" No player data folders to choose from.")
                    controls = if (size.width < COMPACT_REVIEW_WIDTH) "Enter Migrate | Space"
                    else "↑↓ Move | Space: Toggle | Enter: Migrate | Esc: Back"
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
                    row(" F10 lets you choose which player data folders to skip. Close both games first.")
                    if (query.isNotBlank() && files.isEmpty()) row(" No matching config files.")
                    val pane = renderReviewPane(
                        terminal,
                        files, fileCursor, changeCursor, focusFiles, choices, review?.playerData.orEmpty(),
                        width, size.height - rows.size - FOOTER_ROWS, scroll,
                    )
                    rows += pane.rows
                    scroll = pane.scroll
                    controls = if (size.width < COMPACT_REVIEW_WIDTH) "F10 Folders | Tab | ← →"
                    else "Tab Focus | ↑↓ Move | ← Old | → New | PgUp PgDn | F5 All new | F10 Folders | Esc"
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
                cancelling = true
                apply.cancel()
                continue
            }
            if (key.ctrl || key.alt) continue
            if (key.key == KEY_ESCAPE && apply != null && applied == null) {
                cancelling = true
                apply.cancel()
                continue
            }
            if (applied?.isSuccess == true || review?.migrationComplete == true) {
                when (key.key) {
                    KEY_ESCAPE -> return UpdateFlowExit.MENU
                    KEY_ENTER -> return UpdateFlowExit.BACK
                }
                continue
            }
            if (choosingFolders && review != null) {
                when (key.key) {
                    KEY_ESCAPE, KEY_LEFT -> {
                        if (keepNewConfigs) return UpdateFlowExit.BACK
                        choosingFolders = false
                    }
                    KEY_UP -> folderCursor = (folderCursor - 1).coerceAtLeast(0)
                    KEY_DOWN -> folderCursor = (folderCursor + 1).coerceAtMost(review.playerFolders.lastIndex.coerceAtLeast(0))
                    KEY_MINECRAFT_SETTINGS -> includeMinecraftSettings = !includeMinecraftSettings
                    " ", "Space" -> review.playerFolders.getOrNull(folderCursor)?.let { path ->
                        if ('/' !in path || path.substringBefore('/') !in ignoredFolders) {
                            if (!ignoredFolders.remove(path)) ignoredFolders.add(path)
                        }
                    }
                    KEY_ENTER -> {
                        val selected = allChanges.mapNotNull { change ->
                            (choices[change.id] ?: change.remembered)?.let { change.id to it }
                        }.toMap()
                        val ignored = ignoredFolders.toSet()
                        val copySettings = includeMinecraftSettings
                        choosingFolders = false
                        apply = BackgroundOperation(Dispatchers.IO) {
                            if (keepNewConfigs) {
                                updater.migratePlayerData(session, review, ignored, copySettings) { progress.value = it }
                            } else {
                                updater.migrateInstance(checkNotNull(session.tested), review, selected, ignored, copySettings) {
                                    progress.value = it
                                }
                            }
                        }
                    }
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
                KEY_APPLY -> choosingFolders = true
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

private fun progressBar(completed: Int, total: Int, unit: String, terminalWidth: Int): String {
    val label = " $completed/$total $unit"
    val width = (terminalWidth - TERMINAL_EDGE_MARGIN - label.length - " []".length)
        .coerceIn(0, MAX_PROGRESS_BAR_WIDTH)
    if (width == 0) return label
    val filled = if (total == 0) width else (completed.toLong() * width / total).toInt()
    return "$label [${"#".repeat(filled)}${"-".repeat(width - filled)}]"
}
