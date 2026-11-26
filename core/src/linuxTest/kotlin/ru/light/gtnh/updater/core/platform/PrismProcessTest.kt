package ru.light.gtnh.updater.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import platform.posix.chmod
import ru.light.gtnh.updater.core.model.PrismProfile

class PrismProcessTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun startsAPortableLauncherAndPreservesArguments() {
        val fs = FileSystem.SYSTEM
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtnh-prism's test-${Uuid.random()}"
        try {
            val executable = directory / "bin/prismlauncher"
            val output = directory / "received"
            fs.createDirectories(executable.parent!!)
            fs.write(executable) {
                writeUtf8("#!/bin/sh\noutput=\$1\nshift\nprintf '%s\\n' \"\$@\" > \"\$output\"\n")
            }
            val ownerReadWriteExecute = 0x1c0u
            assertEquals(0, chmod(executable.toString(), ownerReadWriteExecute))
            val profile = PrismProfile(directory, directory / "instances", flatpak = false)
            val arguments = listOf("--dir", directory.toString(), "--launch", "test;instance")

            startPrismProcess(profile, listOf(output.toString()) + arguments)

            val expected = arguments.joinToString("\n", postfix = "\n")
            val actual = runBlocking {
                withTimeout(3.seconds) {
                    var content: String
                    do {
                        delay(20.milliseconds)
                        content = if (fs.exists(output)) fs.read(output) { readUtf8() } else ""
                    } while (content.lines().size < expected.lines().size)
                    content
                }
            }
            assertEquals(expected, actual)
        } finally {
            fs.deleteRecursively(directory)
        }
    }
}
