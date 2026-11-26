package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.model.InstanceCopy
import ru.light.gtnh.updater.core.model.InstanceCopyKind

internal const val KEY_COPIES = "F9"
private const val KEY_REFRESH_COPIES = "F5"
private const val KEY_DELETE = "Delete"
private const val KEY_CONFIRM_DELETE = "y"
private const val HEADER_ROWS = 3
private const val FOOTER_ROWS = 3

internal fun manageCopies(terminal: Terminal, input: TerminalInput, updater: Updater): Boolean {
    fun scan() = BackgroundOperation(Dispatchers.IO) { updater.listInstanceCopies() }
    var inventory: BackgroundOperation<List<InstanceCopy>>? = scan()
    var deletion: BackgroundOperation<Unit>? = null
    var copies = emptyList<InstanceCopy>()
    var confirmation: InstanceCopy? = null
    var status = ""
    var cursor = 0
    var offset = 0
    var exitRequested = false
    var frame: CopyFrame? = null

    try {
        while (true) {
            inventory?.outcome()?.let { outcome ->
                inventory = null
                outcome.fold(
                    onSuccess = {
                        copies = it
                        cursor = cursor.coerceAtMost(copies.lastIndex.coerceAtLeast(0))
                    },
                    onFailure = {
                        copies = emptyList()
                        status = "Could not list copies. ${it.message.orEmpty().terminalText()}"
                    },
                )
            }
            deletion?.outcome()?.let { outcome ->
                deletion = null
                if (exitRequested) return true
                status = outcome.fold(
                    onSuccess = { "Copy deleted." },
                    onFailure = { "Could not delete copy. ${it.message.orEmpty().terminalText()}" },
                )
                inventory = scan()
            }

            val size = terminal.updateSize()
            val width = (size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
            val rowsAvailable = (size.height - HEADER_ROWS - FOOTER_ROWS).coerceAtLeast(1)
            val selected = copies.getOrNull(cursor)
            if (cursor < offset) offset = cursor
            if (cursor >= offset + rowsAvailable) offset = cursor - rowsAvailable + 1
            val pending = confirmation
            val rows = buildList {
                add(" GTNH UPDATER  /  Instance copies" to HIGHLIGHT)
                when {
                    deletion != null -> add(" Deleting copy..." to SECTION_STYLE)
                    pending != null -> {
                        add(" Permanently delete this ${pending.kind.label.lowercase()}?" to SECTION_STYLE)
                        pending.path.toString().terminalText().chunked((width - 1).coerceAtLeast(1)).forEach {
                            add(" $it" to "")
                        }
                        add(" Close Minecraft and Prism or MultiMC before confirming." to "")
                    }
                    else -> {
                        add(" ${if (inventory != null) "Scanning instance directories..." else status}" to SECTION_STYLE)
                        if (copies.isEmpty() && inventory == null) add(" No managed copies found." to "")
                        for (index in offset until minOf(offset + rowsAvailable, copies.size)) {
                            val copy = copies[index]
                            val marker = if (index == cursor) ">" else " "
                            val protection = if (copy.protection != null) " [protected]" else ""
                            add(" $marker ${copy.kind.label}$protection  ${copy.path.name.terminalText()}" to
                                if (index == cursor) HIGHLIGHT else "")
                        }
                        selected?.let { copy ->
                            add(" ${copy.protection.orEmpty()}" to SECTION_STYLE)
                            add(" ${copy.path.toString().terminalText()}" to "")
                        }
                    }
                }
            }
            val controls = when {
                deletion != null -> ""
                pending != null -> "Y: Delete permanently | Esc: Cancel"
                selected?.protection != null || selected == null -> "↑↓ Move | F5 Refresh | Esc Back"
                else -> "↑↓ Move | Del Delete | F5 Refresh | Esc Back"
            }
            val next = CopyFrame(rows, controls, size.width, size.height)
            if (next != frame) {
                renderScreen(terminal, rows, controls)
                frame = next
            }

            val key = input.readKeyOrNull() ?: continue
            if (key.requestsExit()) {
                if (deletion == null) return true
                exitRequested = true
                continue
            }
            if (key.ctrl || key.alt || deletion != null) continue
            if (pending != null) {
                when {
                    key.key == KEY_ESCAPE || key.key == KEY_LEFT -> confirmation = null
                    key.key.equals(KEY_CONFIRM_DELETE, ignoreCase = true) -> {
                        confirmation = null
                        deletion = BackgroundOperation(Dispatchers.IO) { updater.deleteInstanceCopy(pending.path) }
                    }
                }
                continue
            }
            when (key.key) {
                KEY_ESCAPE, KEY_LEFT -> return false
                KEY_UP -> cursor = (cursor - 1).coerceAtLeast(0)
                KEY_DOWN -> cursor = (cursor + 1).coerceAtMost(copies.lastIndex.coerceAtLeast(0))
                KEY_REFRESH_COPIES -> if (inventory == null) {
                    status = ""
                    inventory = scan()
                }
                KEY_DELETE -> if (inventory == null && selected?.protection == null) confirmation = selected
            }
        }
    } finally {
        inventory?.cancel()
    }
}

private data class CopyFrame(val rows: List<Pair<String, String>>, val controls: String, val width: Int, val height: Int)

private val InstanceCopyKind.label: String
    get() = when (this) {
        InstanceCopyKind.STAGING -> "Staging"
        InstanceCopyKind.TEST -> "Test instance"
        InstanceCopyKind.BACKUP -> "Backup"
        InstanceCopyKind.DISPLACED -> "Displaced instance"
    }
