package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.RawModeScope
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val FUNCTION_KEY_PREFIX = "f"
private val FUNCTION_KEY_TIMEOUT = 3.seconds

internal class TerminalInput(private val read: () -> KeyboardEvent?) {
    constructor(raw: RawModeScope) : this({ raw.readKeyOrNull(INPUT_POLL_INTERVAL) })

    private var prefixDeadline: TimeMark? = null

    fun readKeyOrNull(): KeyboardEvent? {
        val event = read() ?: return null
        if (event.ctrl && !event.alt && !event.shift && event.key.equals(FUNCTION_KEY_PREFIX, ignoreCase = true)) {
            prefixDeadline = TimeSource.Monotonic.markNow() + FUNCTION_KEY_TIMEOUT
            return null
        }

        val prefixed = prefixDeadline?.hasNotPassedNow() == true
        prefixDeadline = null
        val digit = event.key.singleOrNull()?.takeIf { it in '0'..'9' }
        val functionAlias = !event.ctrl && !event.shift && (event.alt || prefixed)
        if (digit != null && functionAlias) return KeyboardEvent(if (digit == '0') "F10" else "F$digit")
        return event
    }
}
