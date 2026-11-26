package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.model.PackVersion

private const val BOLD_STYLE = "1"
private const val DIM_STYLE = "2"
private const val VERSION_COLUMN_WIDTH = 23
private const val SEARCH_PROMPT = " Search > "
private const val SEARCH_CURSOR = "_"
private const val CATALOG_REFRESHING = "Refreshing catalog..."
internal const val VERSION_LIST_FOOTER_ROWS = 4
private const val ZIP_HEADER_ROWS = 6
private const val ZIP_COMPACT_HEADER_ROWS = 2
private const val ZIP_FOOTER_ROWS = 2

private val PackChannel.displayName: String
    get() = when (this) {
        PackChannel.STABLE -> "Stable"
        PackChannel.BETA -> "Beta / RC"
        PackChannel.DAILY -> "Daily"
    }

internal fun renderChannels(
    terminal: Terminal,
    channels: List<PackChannel>,
    cursor: Int,
    canResume: Boolean,
    canRollback: Boolean,
    recoveryErrors: List<String> = emptyList(),
) {
    val rows = buildList {
        add(" GTNH UPDATER  /  Prism & MultiMC" to HIGHLIGHT)
        add("" to "")
        val width = (terminal.size.width - TERMINAL_EDGE_MARGIN - 1).coerceAtLeast(1)
        for (error in recoveryErrors) {
            terminal.wrapReviewLine(error.terminalText(), width).forEach { add(" $it" to SECTION_STYLE) }
        }
        add(" CHANNELS" to SECTION_STYLE)
        for ((index, channel) in channels.withIndex()) {
            val selected = cursor == index
            val marker = if (selected) ">" else " "
            add(" $marker  ${channel.displayName}" to if (selected) HIGHLIGHT else "")
        }
        add("" to "")
    }
    val resume = if (canResume) " | F6: Resume update" else ""
    val rollback = if (canRollback) " | F8: Roll back" else ""
    renderScreen(terminal, rows, "Enter: Open | F2: Settings | F9: Copies$resume$rollback | Esc: Quit")
}

internal fun renderBrowser(
    terminal: Terminal,
    channel: PackChannel,
    query: String,
    matches: List<PackVersion>,
    cursor: Int,
    offset: Int,
    rowCount: Int,
    catalog: ChannelCatalog,
    refreshing: Boolean,
    confirmed: PackVersion?,
    archiveCursor: Int,
    selectedArchive: ClientArchive?,
) {
    val width = (terminal.size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
    val height = terminal.size.height.coerceAtLeast(1)
    val rows = mutableListOf<Pair<String, String>>()
    fun row(text: String, style: String = "") {
        rows += text to style
    }

    val footer: String
    row(" GTNH UPDATER  /  ${channel.displayName}", HIGHLIGHT)
    if (confirmed != null) {
        val compact = height <= ZIP_HEADER_ROWS + ZIP_FOOTER_ROWS
        if (!compact) row("")
        row(" ${confirmed.id}  [${channel.displayName}]", SECTION_STYLE)
        if (!compact) row("")
        if (selectedArchive != null) {
            row(" Selected ZIP  /  ${selectedArchive.label}", SECTION_STYLE)
            row("")
            selectedArchive.url.chunked((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)).forEach { row(" $it") }
            row("")
            footer = "Right/Enter: Download   Left/Esc: ZIP options"
        } else {
            val archives = catalog.archives[confirmed.id].orEmpty()
            if (!compact) row(" PRISM / MULTIMC ZIP", SECTION_STYLE)
            if (archives.isEmpty()) {
                row(" Direct ZIP links are unavailable in this catalog.")
                confirmed.sourceUrl.chunked((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)).forEach { row(" $it") }
            }
            val headerRows = if (compact) ZIP_COMPACT_HEADER_ROWS else rows.size
            val visibleRows = (height - headerRows - ZIP_FOOTER_ROWS).coerceAtLeast(0)
            val start = (archiveCursor - visibleRows + 1).coerceAtLeast(0)
            for (index in start until minOf(start + visibleRows, archives.size)) {
                val selected = archiveCursor == index
                val marker = if (selected) ">" else " "
                row(" $marker  ${archives[index].label}", if (selected) HIGHLIGHT else "")
            }
            row("")
            footer = "Up/Down: Move   Right/Enter: ZIP   Left/Esc: Versions"
        }
    } else {
        val queryWidth = (width - SEARCH_PROMPT.length - SEARCH_CURSOR.length).coerceAtLeast(1)
        row("$SEARCH_PROMPT${query.takeLast(queryWidth)}$SEARCH_CURSOR", BOLD_STYLE)
        row(" " + "-".repeat((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(0)), HELP_STYLE)
        row(" VERSIONS  ${matches.size}/${catalog.versions.size}", SECTION_STYLE)
        if (matches.isEmpty()) {
            val message = when {
                catalog.versions.isNotEmpty() -> "No matching versions."
                refreshing -> "Loading versions..."
                else -> "No versions available."
            }
            row("   $message", DIM_STYLE)
        }
        for (index in offset until minOf(offset + rowCount, matches.size)) {
            val version = matches[index]
            val selected = index == cursor
            val marker = if (selected) ">" else " "
            row(" $marker  ${version.id.padEnd(VERSION_COLUMN_WIDTH)} ${version.channel.label.uppercase()}", if (selected) HIGHLIGHT else "")
        }
        while (rows.size < (height - VERSION_LIST_FOOTER_ROWS)) row("")
        row(" " + "-".repeat((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(0)), HELP_STYLE)
        footer = "Type: Search   Right/Enter: Open   F5: Refresh   Left/Esc: Channels"
        row(" ${if (refreshing) CATALOG_REFRESHING else catalog.status}", DIM_STYLE)
    }

    renderScreen(terminal, rows, footer)
}
