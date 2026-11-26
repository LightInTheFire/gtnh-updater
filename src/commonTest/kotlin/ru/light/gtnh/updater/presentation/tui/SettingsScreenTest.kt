package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.light.gtnh.updater.core.model.AppSettings

class SettingsScreenTest {
    @Test
    fun savesDefaultsToFinishFirstRun() {
        val saved = mutableListOf<AppSettings>()
        val recorder = TerminalRecorder()
        recorder.inputEvents.add(KeyboardEvent("Escape"))
        val terminal = Terminal(terminalInterface = recorder)

        val result = terminal.enterRawMode().use {
            runSettingsScreen(terminal, TerminalInput(it), { saved += it }, AppSettings(), firstRun = true)
        }

        assertEquals(SettingsSelection.Saved(AppSettings()), result)
        assertEquals(AppSettings(), saved.single())
    }

    @Test
    fun masksTheTokenAndPersistsTheSelectedToggles() {
        val saved = mutableListOf<AppSettings>()
        val recorder = TerminalRecorder()
        val keys = listOf("Enter") + TEST_TOKEN.map(Char::toString) +
            listOf("Enter", "ArrowDown", "Enter", "ArrowDown", "Enter", "ArrowDown", "Enter")
        recorder.inputEvents.addAll(keys.map(::KeyboardEvent))
        val terminal = Terminal(terminalInterface = recorder)

        val result = terminal.enterRawMode().use {
            runSettingsScreen(terminal, TerminalInput(it), { saved += it }, AppSettings(), firstRun = true)
        }

        val expected = AppSettings(TEST_TOKEN, removeDefaultServerList = true, transferExtraMods = true)
        assertEquals(SettingsSelection.Saved(expected), result)
        assertEquals(expected, saved.single())
        assertTrue(recorder.output().contains("(hidden)"))
        assertFalse(recorder.output().contains(TEST_TOKEN))
        assertFalse(expected.toString().contains(TEST_TOKEN))
    }

    @Test
    fun showsOnlyTheDescriptionOfTheHighlightedSetting() {
        val recorder = TerminalRecorder(width = 100)
        recorder.inputEvents.addAll(listOf("ArrowDown", "ArrowDown", "ArrowDown", "Escape").map(::KeyboardEvent))
        val terminal = Terminal(terminalInterface = recorder)

        terminal.enterRawMode().use {
            runSettingsScreen(terminal, TerminalInput(it), { error("Unexpected settings write") }, AppSettings())
        }

        val frames = recorder.output().split("\u001b[H").filter { it.isNotEmpty() }
        val descriptions = listOf(
            "Authenticates requests",
            "On removes DefaultServerList",
            "On enables extra mod selection",
            "Writes your choices locally",
        )
        assertEquals(descriptions.size, frames.size)
        for ((frame, description) in frames.zip(descriptions)) {
            assertTrue(frame.contains(description))
            assertEquals(1, descriptions.count(frame::contains))
        }
    }

}

private const val TEST_TOKEN = "test-placeholder-token"
