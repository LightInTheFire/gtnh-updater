package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertContains
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.model.PackVersion

class CatalogViewsTest {
    @Test
    fun displaysRecoveryErrorsOnTheChannelScreen() {
        val recorder = TerminalRecorder(width = 80, height = 14)
        val terminal = Terminal(terminalInterface = recorder)
        renderChannels(
            terminal, PackChannel.entries, cursor = 0, canResume = false, canRollback = false,
            recoveryErrors = listOf("Resume failed: Invalid saved instance paths.", "Rollback unavailable: Invalid switch record."),
        )
        assertContains(recorder.output(), "Resume failed: Invalid saved instance paths.")
        assertContains(recorder.output(), "Rollback unavailable: Invalid switch record.")
        assertContains(recorder.output(), "Stable")
    }

    @Test
    fun keepsTheSelectedArchiveVisibleInAShortTerminal() {
        val recorder = TerminalRecorder(width = 100, height = 5)
        val terminal = Terminal(terminalInterface = recorder)
        val version = checkNotNull(PackVersion.parse("2.9.2"))
        val archives = listOf(ClientArchive("Java 17-26", "first.zip"), ClientArchive("Java 8", "second.zip"))
        val catalog = ChannelCatalog(listOf(version), mapOf(version.id to archives), status = "")

        for ((index, archive) in archives.withIndex()) {
            renderBrowser(
                terminal, PackChannel.STABLE, "", listOf(version), cursor = 0, offset = 0, rowCount = 1,
                catalog, refreshing = false, confirmed = version, archiveCursor = index, selectedArchive = null,
            )
            val frame = recorder.output().substringAfterLast("\u001b[H")
            assertContains(frame, ">  ${archive.label}")
            assertContains(frame, "Ctrl+Esc / Ctrl+C: Quit")
        }
    }
}
