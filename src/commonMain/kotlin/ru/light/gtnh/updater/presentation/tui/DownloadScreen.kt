package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CancellationException
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.TransferProgress

private const val PROGRESS_BAR_WIDTH = 20
private const val PERCENT_SCALE = 100
private const val BYTES_PER_MEBIBYTE = 1024L * 1024

internal fun runDownloadScreen(
    terminal: Terminal,
    input: TerminalInput,
    archive: ClientArchive,
    targetVersion: String,
    updater: Updater,
): UpdateFlowExit {
    val transfer = ArchiveDownload(archive, updater)
    var cancelling = false
    var exitRequested = false
    var previousFrame = emptyList<Pair<String, String>>()
    var previousSize = 0 to 0

    try {
        while (true) {
            val outcome = transfer.outcome()
            if (outcome != null && exitRequested) return UpdateFlowExit.QUIT

            val size = terminal.updateSize()
            val width = (size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
            val rows = buildList {
                add(" GTNH UPDATER  /  Download ZIP" to HIGHLIGHT)
                add(" ${archive.label}" to SECTION_STYLE)
                add("" to "")
                if (outcome == null) {
                    val progress = transfer.progress()
                    val status = when {
                        cancelling -> "Cancelling download..."
                        progress == TransferProgress() -> "Checking cache / connecting..."
                        else -> "Downloading..."
                    }
                    add(" $status" to SECTION_STYLE)
                    if (progress != TransferProgress()) add(" ${progress.display()}" to "")
                } else {
                    outcome.fold(
                        onSuccess = { archive ->
                            val status = if (archive.fromCache) " Using cached ZIP" else " Download complete"
                            add(status to SECTION_STYLE)
                            archive.path.toString().terminalText().chunked((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1))
                                .forEach { add(" $it" to "") }
                        },
                        onFailure = { error ->
                            if (cancelling || error is CancellationException) {
                                add(" Download cancelled" to SECTION_STYLE)
                            } else {
                                add(" Download failed" to SECTION_STYLE)
                                val detail = error.message?.terminalText() ?: error::class.simpleName.orEmpty()
                                detail.chunked((width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1))
                                    .forEach { add(" $it" to "") }
                            }
                        },
                    )
                }
            }
            val footer = when {
                outcome == null && !cancelling -> "Left/Esc: Cancel"
                outcome?.isSuccess == true -> "Enter: Prepare update | F3: Install as new | Esc: Back"
                outcome != null -> "Left/Esc: Back"
                else -> ""
            }
            val dimensions = size.width to size.height
            if (rows != previousFrame || dimensions != previousSize) {
                renderScreen(terminal, rows, footer)
                previousFrame = rows
                previousSize = dimensions
            }

            val key = input.readKeyOrNull() ?: continue
            val archivePath = outcome?.getOrNull()?.path
            if (archivePath != null && !key.ctrl && !key.alt) {
                val exit = when (key.key) {
                    KEY_ENTER, KEY_RIGHT -> runPreparationScreen(terminal, input, archivePath, targetVersion, updater)
                    KEY_INSTALL_NEW -> installNewInstance(terminal, input, updater, archivePath, targetVersion)
                    else -> null
                }
                if (exit != null) {
                    if (exit != UpdateFlowExit.BACK) return exit
                    previousFrame = emptyList()
                    continue
                }
            }
            if (key.requestsExit()) {
                if (outcome != null) return UpdateFlowExit.QUIT
                exitRequested = true
            } else if (key.ctrl || key.alt || (key.key != KEY_ESCAPE && key.key != KEY_LEFT)) {
                continue
            } else if (outcome != null) {
                return UpdateFlowExit.BACK
            }
            if (!cancelling) {
                cancelling = true
                transfer.cancel()
            }
        }
    } finally {
        if (transfer.outcome() == null) transfer.cancel()
    }
}

private fun TransferProgress.display(): String {
    val totalBytes = total?.takeIf { it > 0L } ?: return received.displaySize()
    val fraction = (received.toDouble() / totalBytes).coerceIn(0.0, 1.0)
    val filled = (fraction * PROGRESS_BAR_WIDTH).toInt()
    val bar = "#".repeat(filled) + "-".repeat(PROGRESS_BAR_WIDTH - filled)
    return "[$bar] ${(fraction * PERCENT_SCALE).toInt()}%   ${received.displaySize()} / ${totalBytes.displaySize()}"
}

private fun Long.displaySize(): String =
    if (this < BYTES_PER_MEBIBYTE) "$this bytes" else "${this / BYTES_PER_MEBIBYTE} MiB"
