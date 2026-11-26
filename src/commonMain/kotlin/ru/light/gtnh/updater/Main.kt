package ru.light.gtnh.updater

import kotlin.system.exitProcess
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.platform.withUpdaterLock
import ru.light.gtnh.updater.presentation.cli.runCli
import ru.light.gtnh.updater.presentation.tui.runClientMenu

fun main(args: Array<String>) {
    try {
        withUpdaterLock {
            val updater = Updater(APP_VERSION)
            if (args.isEmpty()) runClientMenu(updater) else runCli(args, updater)
        }
    } catch (error: Exception) {
        println(error.message ?: "GTNH Updater failed.")
        exitProcess(1)
    }
}
