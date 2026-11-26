package ru.light.gtnh.updater.core.platform

import ru.light.gtnh.updater.core.model.PrismProfile

internal expect fun startPrismProcess(profile: PrismProfile, arguments: List<String>)

internal fun posixLaunchScript(executable: String, arguments: List<String>): String {
    fun quote(argument: String): String = "'${argument.replace("'", "'\\''")}'"
    val command = (listOf(executable) + arguments).joinToString(" ", transform = ::quote)
    return "command -v ${quote(executable)} >/dev/null 2>&1 && { $command </dev/null >/dev/null 2>&1 & }"
}
