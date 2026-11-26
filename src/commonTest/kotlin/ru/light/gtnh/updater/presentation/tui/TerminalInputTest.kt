package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalInputTest {
    @Test
    fun altDigitsBecomeFunctionKeys() {
        val events = ('1'..'9').map { KeyboardEvent(it.toString(), alt = true) } +
            KeyboardEvent("0", alt = true)
        val input = inputFor(*events.toTypedArray())
        assertEquals((1..10).map { "F$it" }, List(10) { input.readKeyOrNull()?.key })
    }

    @Test
    fun prefixSurvivesAnIdlePollAndDoesNotChangeOrdinaryDigits() {
        val zero = KeyboardEvent("0")
        val input = inputFor(KeyboardEvent("f", ctrl = true), null, zero, zero)

        assertNull(input.readKeyOrNull())
        assertNull(input.readKeyOrNull())
        assertEquals(KeyboardEvent("F10"), input.readKeyOrNull())
        assertEquals(zero, input.readKeyOrNull())
    }

    @Test
    fun aNonDigitClearsThePrefixAndKeepsItsOriginalModifiers() {
        val quit = KeyboardEvent("Escape", ctrl = true)
        val four = KeyboardEvent("4")
        val input = inputFor(KeyboardEvent("f", ctrl = true), quit, four)

        assertNull(input.readKeyOrNull())
        assertEquals(quit, input.readKeyOrNull())
        assertEquals(four, input.readKeyOrNull())
    }

    private fun inputFor(vararg events: KeyboardEvent?): TerminalInput {
        val queue = events.iterator()
        return TerminalInput { if (queue.hasNext()) queue.next() else null }
    }
}
