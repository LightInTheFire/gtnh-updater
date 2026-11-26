package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalWidthTest {
    private val terminal = Terminal(terminalInterface = TerminalRecorder(width = 24))

    @Test
    fun fitsWideCharactersAndKeepsSurrogatePairsTogether() {
        assertEquals("界" to 2, terminal.fitTerminalCells("界界", 3))
        assertEquals(listOf("界", "界", "😀a"), terminal.wrapReviewLine("界界😀a", 3))
        assertEquals(listOf("abcdef", "xyz"), terminal.wrapReviewLine("abcdefxyz", 6))
    }
}
