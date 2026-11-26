package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.Path
import okio.Path.Companion.toPath
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.model.InstalledInstance
import ru.light.gtnh.updater.core.model.PreparationProgress

internal const val KEY_INSTALL_NEW = "F3"
private const val KEY_CUSTOM_PATH = "F2"
private const val DESTINATION_HEADER_ROWS = 4
private const val DESTINATION_FOOTER_ROWS = 2

internal fun installNewInstance(
    terminal: Terminal,
    input: TerminalInput,
    updater: Updater,
    archive: Path,
    targetVersion: String,
): UpdateFlowExit {
    val scan = BackgroundOperation(Dispatchers.IO) { updater.installationDirectories().sortedBy(Path::toString) }
    val progress = atomic(PreparationProgress())
    var installation: BackgroundOperation<InstalledInstance>? = null
    var customPath: String? = null
    var pathError: String? = null
    var cursor = 0
    var offset = 0
    var cancelling = false
    var exitRequested = false
    var previousFrame = emptyList<Pair<String, String>>()
    var previousFooter = ""
    var previousSize = terminal.size

    try {
        while (true) {
            val scanned = scan.outcome()
            val directories = scanned?.getOrNull().orEmpty()
            val operation = installation
            val result = operation?.outcome()
            if (exitRequested && result != null) return UpdateFlowExit.QUIT

            val size = terminal.updateSize()
            val width = (size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
            val visible = (size.height - DESTINATION_HEADER_ROWS - DESTINATION_FOOTER_ROWS).coerceAtLeast(1)
            if (cursor < offset) offset = cursor
            if (cursor >= offset + visible) offset = cursor - visible + 1
            val edit = customPath
            val rows = buildList {
                add(" GTNH UPDATER  /  Install as new" to HIGHLIGHT)
                add(" Instance: GTNH $targetVersion" to SECTION_STYLE)
                add("" to "")
                when {
                    result?.isSuccess == true -> {
                        val installed = result.getOrThrow()
                        add(" Installation complete" to SECTION_STYLE)
                        add(" Installed as ${installed.name.terminalText()}" to "")
                        installed.directory.toString().terminalText().chunked((width - 1).coerceAtLeast(1))
                            .forEach { add(" $it" to "") }
                        add(" Open the new instance in Prism Launcher or MultiMC." to "")
                    }
                    result?.isFailure == true -> {
                        val error = result.exceptionOrNull()
                        add((if (error is CancellationException) " Installation cancelled" else " Installation failed") to SECTION_STYLE)
                        if (error !is CancellationException) error?.message.orEmpty().terminalText()
                            .chunked((width - 1).coerceAtLeast(1)).forEach { add(" $it" to "") }
                    }
                    operation != null -> {
                        add((if (cancelling) " Cancelling installation..." else " Extracting client ZIP...") to SECTION_STYLE)
                        val current = progress.value
                        if (current.totalEntries > 0) {
                            add(" ${current.extractedEntries} / ${current.totalEntries} entries" to "")
                        }
                    }
                    edit != null -> {
                        val prefix = " Instances path > "
                        val room = (width - prefix.length - "_".length).coerceAtLeast(1)
                        add("$prefix${edit.takeLast(room)}_" to HIGHLIGHT)
                        add(" Enter the full path to an existing launcher instances folder." to "")
                        pathError?.let { add(" $it" to SECTION_STYLE) }
                    }
                    else -> {
                        add(" Choose a launcher instances folder" to SECTION_STYLE)
                        when {
                            scanned == null -> add(" Finding launcher folders..." to "")
                            scanned.isFailure -> add(" Could not detect folders. Use F2 to enter a path." to "")
                            directories.isEmpty() -> add(" No launcher folders found. Use F2 to enter a path." to "")
                        }
                        for (index in offset until minOf(offset + visible, directories.size)) {
                            val marker = if (index == cursor) ">" else " "
                            add(" $marker ${directories[index].toString().terminalText()}" to if (index == cursor) HIGHLIGHT else "")
                        }
                    }
                }
            }
            val footer = when {
                result?.isSuccess == true -> "Enter/Esc: Main menu"
                result?.isFailure == true -> "Enter/Esc: Choose folder"
                operation != null -> if (cancelling) "" else "Esc: Cancel"
                edit != null -> "Enter: Install | Esc: Back"
                directories.isEmpty() -> "F2: Enter path | Esc: Back"
                else -> "↑↓ Move | Enter: Install | F2: Enter path | Esc: Back"
            }
            if (rows != previousFrame || footer != previousFooter || size != previousSize) {
                renderScreen(terminal, rows, footer)
                previousFrame = rows
                previousFooter = footer
                previousSize = size
            }

            val key = input.readKeyOrNull() ?: continue
            if (key.requestsExit()) {
                if (operation == null || result != null) return UpdateFlowExit.QUIT
                exitRequested = true
                cancelling = true
                operation.cancel()
                continue
            }
            if (key.ctrl || key.alt) continue
            if (operation != null) {
                if (result == null && key.key == KEY_ESCAPE && !cancelling) {
                    cancelling = true
                    operation.cancel()
                } else if (result != null && key.key in setOf(KEY_ESCAPE, KEY_ENTER)) {
                    if (result.isSuccess) return UpdateFlowExit.MENU
                    installation = null
                    cancelling = false
                }
                continue
            }
            val destination = when (key.key) {
                KEY_ESCAPE -> {
                    if (edit == null) return UpdateFlowExit.BACK
                    customPath = null
                    pathError = null
                    null
                }
                KEY_CUSTOM_PATH -> {
                    if (edit == null) customPath = ""
                    null
                }
                KEY_ENTER -> {
                    if (edit == null) directories.getOrNull(cursor) else {
                        runCatching { edit.trim().toPath().takeIf(Path::isAbsolute) }.getOrNull().also {
                            if (it == null) pathError = "Enter an absolute folder path."
                        }
                    }
                }
                else -> {
                    if (edit == null) {
                        when (key.key) {
                            KEY_UP -> cursor = (cursor - 1).coerceAtLeast(0)
                            KEY_DOWN -> cursor = (cursor + 1).coerceAtMost(directories.lastIndex.coerceAtLeast(0))
                        }
                    } else {
                        when {
                            key.key == KEY_BACKSPACE -> customPath = edit.dropLast(1)
                            key.key.length == 1 && !key.key.single().isISOControl() -> customPath = edit + key.key
                        }
                        pathError = null
                    }
                    null
                }
            }
            if (destination != null) {
                progress.value = PreparationProgress()
                installation = BackgroundOperation(Dispatchers.IO) {
                    updater.installClient(archive, destination, targetVersion) { progress.value = it }
                }
            }
        }
    } finally {
        scan.cancel()
        if (installation?.outcome() == null) installation?.cancel()
    }
}
