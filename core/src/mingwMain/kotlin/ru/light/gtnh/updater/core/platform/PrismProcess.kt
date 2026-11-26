package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.CloseHandle
import platform.windows.CreateProcessW
import platform.windows.FALSE
import platform.windows.GetLastError
import platform.windows.PROCESS_INFORMATION
import platform.windows.STARTUPINFOW
import ru.light.gtnh.updater.core.model.PrismProfile

private const val PRISM_EXECUTABLE = "prismlauncher.exe"

@OptIn(ExperimentalForeignApi::class)
internal actual fun startPrismProcess(profile: PrismProfile, arguments: List<String>) {
    val paths = buildList {
        add(profile.root / PRISM_EXECUTABLE)
        for (variable in listOf("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            val directory = readEnvironmentVariable(variable)?.toPath() ?: continue
            add(directory / "PrismLauncher" / PRISM_EXECUTABLE)
            add(directory / "Programs/PrismLauncher" / PRISM_EXECUTABLE)
        }
        for (directory in readEnvironmentVariable("PATH").orEmpty().split(';')) {
            val path = directory.takeIf { it.isNotBlank() }?.removeSurrounding("\"")?.toPath() ?: continue
            if (path.isAbsolute) add(path / PRISM_EXECUTABLE)
        }
    }
    val executable = paths.firstOrNull { FileSystem.SYSTEM.metadataOrNull(it)?.isRegularFile == true }
        ?: error("Could not find Prism Launcher.")
    val command = (listOf(executable.toString()) + arguments).joinToString(" ", transform = ::windowsArgument)
    memScoped {
        val startup = cValue<STARTUPINFOW> { cb = sizeOf<STARTUPINFOW>().toUInt() }
        val process = alloc<PROCESS_INFORMATION>()
        val created = CreateProcessW(
            executable.toString(), command.wcstr.getPointer(this), null, null, FALSE, 0u, null, null,
            startup.ptr, process.ptr,
        )
        check(created != FALSE) { "Could not start Prism Launcher (Windows error ${GetLastError()})." }
        CloseHandle(process.hThread)
        CloseHandle(process.hProcess)
    }
}

private fun windowsArgument(argument: String): String = buildString {
    append('"')
    var backslashes = 0
    for (character in argument) {
        when (character) {
            '\\' -> backslashes++
            '"' -> {
                repeat(backslashes * 2 + 1) { append('\\') }
                append(character)
                backslashes = 0
            }
            else -> {
                repeat(backslashes) { append('\\') }
                append(character)
                backslashes = 0
            }
        }
    }
    repeat(backslashes * 2) { append('\\') }
    append('"')
}
