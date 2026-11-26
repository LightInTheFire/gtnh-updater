package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
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

internal fun Terminal.fitTerminalCells(text: String, width: Int): Pair<String, Int> {
    if (width <= 0) return "" to 0
    val prefix = text.take(width).trimIncompleteSurrogate()
    if (prefix.all { it in ' '..'~' }) return prefix to prefix.length
    fun cells(value: String): Int = Text(value).measure(this, width).max
    val prefixCells = cells(prefix)
    if (prefixCells <= width) return prefix to prefixCells
    var low = 0
    var high = prefix.length
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (cells(prefix.take(middle).trimIncompleteSurrogate()) <= width) low = middle
        else high = middle - 1
    }
    val fitted = prefix.take(low).trimIncompleteSurrogate()
    return fitted to cells(fitted)
}

private fun String.trimIncompleteSurrogate(): String =
    if (lastOrNull()?.isHighSurrogate() == true) dropLast(1) else this

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
            val (fitted, cells) = terminal.fitTerminalCells(text, cellWidth)
            append(fitted)
            if (fill || style == HIGHLIGHT) append(" ".repeat((cellWidth - cells).coerceAtLeast(0)))
        }

        append(CURSOR_HOME)
        for ((index, item) in frame.take(height).withIndex()) {
            if (index > 0) append("\r\n")
            val right = item.right
            if (right == null || width < MIN_SPLIT_ROW_WIDTH) {
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

private const val MIN_SPLIT_ROW_WIDTH = 4
