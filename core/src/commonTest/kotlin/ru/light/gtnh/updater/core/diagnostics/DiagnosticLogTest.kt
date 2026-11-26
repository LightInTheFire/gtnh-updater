package ru.light.gtnh.updater.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import okio.FileSystem

class DiagnosticLogTest {
    @Test
    fun recordsOutcomesWithoutExceptionMessagesAndRotatesFullLog() {
        val fs = FileSystem.SYSTEM
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-log-test-${Uuid.random()}"
        val path = home / "updater.log"
        try {
            val log = DiagnosticLog("0.2.1", fs, home)
            assertEquals(42, log.track("success") { 42 })
            assertFailsWith<IllegalStateException> {
                log.track("failure") { throw IllegalStateException("private path or token", IllegalArgumentException("private cause")) }
            }
            assertFailsWith<CancellationException> {
                log.track("cancel") { throw CancellationException("private reason") }
            }
            val contents = fs.read(path) { readUtf8() }
            assertTrue("operation=application started version=0.2.1" in contents)
            assertTrue("operation=success completed" in contents)
            assertTrue("operation=failure failed type=IllegalStateException cause=IllegalArgumentException" in contents)
            assertTrue("operation=cancel cancelled" in contents)
            assertFalse("private" in contents)

            val fullLog = "x".repeat(1024 * 1024)
            fs.write(path) { writeUtf8(fullLog) }
            DiagnosticLog("0.2.1", fs, home)
            assertEquals(fullLog, fs.read(home / "updater.previous.log") { readUtf8() })
            assertTrue("started version=0.2.1" in fs.read(path) { readUtf8() })
        } finally {
            fs.deleteRecursively(home)
        }
    }
}
