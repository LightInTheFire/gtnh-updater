package ru.light.gtnh.updater

import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.presentation.cli.runCli
import ru.light.gtnh.updater.presentation.tui.runClientMenu

fun main(args: Array<String>) {
    val updater = Updater()
    if (args.isEmpty()) runClientMenu(updater) else runCli(args, updater)
}
