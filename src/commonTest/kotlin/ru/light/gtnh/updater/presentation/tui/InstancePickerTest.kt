package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import ru.light.gtnh.updater.core.InstanceBrowser
import ru.light.gtnh.updater.core.catalog.fuzzySearch
import ru.light.gtnh.updater.core.model.InstanceChoice

class InstancePickerTest {
    @Test
    fun pickerFiltersDetectedInstancesAndPlacesControlsOnTheLastRow() {
        val target = "/instances/b".toPath()
        val finder = BrowserFixture(
            detected = listOf(InstanceChoice("/instances/a".toPath(), "GTNH Alpha"), InstanceChoice(target, "GTNH Beta")),
        )
        val recorder = TerminalRecorder(height = 16, width = 100)
        val terminal = Terminal(terminalInterface = recorder)

        val selected = pickInstance(terminal, recorder.keys(
            "GTNH Alpha" to "b", "Search > b_" to "t", ">  GTNH Beta" to "ArrowRight",
        ), "2.9.2", finder)

        assertEquals(InstanceSelection.Selected(target), selected)
        val frame = recorder.output().substringAfterLast("\u001b[H").split("\r\n")
        assertEquals(16, frame.size)
        assertTrue(frame.dropLast(1).last().contains("Tab: Browse folders"))
        assertTrue(frame.last().contains("Ctrl+Esc / Ctrl+C: Quit"))
    }

    @Test
    fun pickerCanFindAnInstanceByBrowsingAndFilteringFolders() {
        val library = "/test/External Packs".toPath()
        val target = library / "GregTech New Horizons"
        val finder = BrowserFixture(folders = mapOf(library.parent!! to listOf(library), library to listOf(target)), target = target)
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)

        val selected = pickInstance(terminal, recorder.keys(
            "Tab: Browse folders" to "Tab",
            "Left: Parent" to "x",
            "Search > x_" to "p",
            ">  External Packs" to "ArrowRight",
            "/test/External Packs" to "g",
            "Search > g_" to "t",
            "Search > gt_" to "h",
            ">  GregTech New Horizons" to "ArrowRight",
        ), "2.9.2", finder)

        assertEquals(InstanceSelection.Selected(target), selected)
        assertTrue(recorder.output().contains("Left: Parent"))
    }

    @Test
    fun exitsWhileReadingAFolder() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finder = object : InstanceBrowser by BrowserFixture() {
            override fun searchFolders(directory: Path, query: String): List<Path> = runBlocking {
                started.complete(Unit)
                release.await()
                emptyList()
            }
        }
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)
        val start = TimeSource.Monotonic.markNow()
        var opened = false

        try {
            val selected = pickInstance(terminal, {
                check(start.elapsedNow() < 5.seconds) { "Folder scan did not start" }
                when {
                    !opened -> {
                        opened = true
                        KeyboardEvent("Tab")
                    }
                    started.isCompleted -> KeyboardEvent("Escape", ctrl = true)
                    else -> null
                }
            }, "2.9.2", finder)

            assertEquals(InstanceSelection.Quit, selected)
            assertTrue(recorder.output().contains("Loading folders..."))
        } finally {
            release.complete(Unit)
        }
    }
}

private fun TerminalRecorder.keys(vararg steps: Pair<String, String>): () -> KeyboardEvent? {
    val pending = steps.iterator()
    var next = if (pending.hasNext()) pending.next() else null
    val started = TimeSource.Monotonic.markNow()
    return {
        val step = next
        check(started.elapsedNow() < 5.seconds) { "Timed out waiting for ${step?.first ?: "selection"}" }
        if (step != null && output().substringAfterLast("\u001b[H").contains(step.first)) {
            next = if (pending.hasNext()) pending.next() else null
            KeyboardEvent(step.second)
        } else {
            null
        }
    }
}

private class BrowserFixture(
    private val detected: List<InstanceChoice> = emptyList(),
    private val folders: Map<Path, List<Path>> = emptyMap(),
    private val target: Path? = null,
) : InstanceBrowser {
    override fun discover(): List<InstanceChoice> = detected
    override fun startingDirectory(): Path = "/test".toPath()
    override fun searchFolders(directory: Path, query: String): List<Path> =
        fuzzySearch(folders[directory].orEmpty(), query) { listOf(it.name) }
    override fun instance(path: Path): InstanceChoice? =
        if (path == target) InstanceChoice(path, path.name) else null
}
