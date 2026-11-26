package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.terminal.Terminal
import kotlin.time.Duration.Companion.milliseconds

private const val ESC = "\u001b"
private const val CURSOR_HOME = "$ESC[H"
private const val CLEAR_LINE_FROM_CURSOR = "$ESC[K"
private const val CLEAR_SCREEN_FROM_CURSOR = "$ESC[J"
private const val RESET_STYLE = "$ESC[0m"
internal const val HIGHLIGHT = "1;30;46"
internal const val SECTION_STYLE = "1;36"
internal const val HELP_STYLE = "36"
internal const val KEY_ESCAPE = "Escape"
internal const val KEY_UP = "ArrowUp"
internal const val KEY_DOWN = "ArrowDown"
internal const val KEY_LEFT = "ArrowLeft"
internal const val KEY_RIGHT = "ArrowRight"
internal const val KEY_ENTER = "Enter"
internal const val KEY_BACKSPACE = "Backspace"
internal val INPUT_POLL_INTERVAL = 100.milliseconds
internal const val TERMINAL_EDGE_MARGIN = 1
private const val GLOBAL_QUIT_HINT = " Ctrl+Esc / Ctrl+C: Quit | F-keys: Alt+digit or Ctrl+F then digit (0=F10)"
internal const val TERMINAL_ENTER = "$ESC[?1049h$ESC[?25l$ESC[>1u"
internal const val TERMINAL_EXIT = "$ESC[<1u$ESC[0m$ESC[?25h$ESC[?1049l"

internal fun KeyboardEvent.requestsExit(): Boolean =
    isCtrlC || (key == KEY_ESCAPE && ctrl && !alt && !shift)

internal fun String.terminalText(): String = filterNot(Char::isISOControl)

internal data class TerminalRow(
    val left: Pair<String, String>,
    val right: Pair<String, String>? = null,
    val leftWidth: Int = 0,
)

internal fun renderScreen(terminal: Terminal, rows: List<Pair<String, String>>, controls: String = "") =
    renderScreenRows(terminal, rows.map { TerminalRow(it) }, controls)

internal fun renderScreenRows(terminal: Terminal, rows: List<TerminalRow>, controls: String = "") {
    val width = (terminal.size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
    val height = terminal.size.height.coerceAtLeast(1)
    val footer = buildList {
        if (controls.isNotBlank()) add(TerminalRow(" $controls" to HELP_STYLE))
        add(TerminalRow(GLOBAL_QUIT_HINT to HELP_STYLE))
    }.takeLast(height)
    val bodyHeight = height - footer.size
    val frame = buildList {
        addAll(rows.take(bodyHeight))
        while (size < bodyHeight) add(TerminalRow("" to ""))
        addAll(footer)
    }
    val screen = buildString {
        fun appendCell(cell: Pair<String, String>, cellWidth: Int, fill: Boolean = false) {
            val (text, style) = cell
            if (style.isNotEmpty()) append("$ESC[${style}m")
            append(text.take(cellWidth))
            if (fill || style == HIGHLIGHT) append(" ".repeat((cellWidth - text.length).coerceAtLeast(0)))
        }

        append(CURSOR_HOME)
        for ((index, item) in frame.take(height).withIndex()) {
            if (index > 0) append("\r\n")
            val right = item.right
            if (right == null) {
                appendCell(item.left, width)
            } else {
                val separator = " │ "
                val leftWidth = item.leftWidth.coerceIn(1, (width - separator.length).coerceAtLeast(1))
                appendCell(item.left, leftWidth, fill = true)
                append(RESET_STYLE).append(separator)
                appendCell(right, (width - leftWidth - separator.length).coerceAtLeast(0))
            }
            append(RESET_STYLE).append(CLEAR_LINE_FROM_CURSOR)
        }
        append(CLEAR_SCREEN_FROM_CURSOR)
    }
    terminal.rawPrint(screen)
}
