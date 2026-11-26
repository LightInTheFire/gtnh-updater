package ru.light.gtnh.updater.presentation.cli

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import ru.light.gtnh.updater.core.Updater

private const val INVALID_ARGUMENTS_EXIT_CODE = 2
private const val PREPARATION_FAILURE_EXIT_CODE = 1

internal fun runCli(args: Array<String>, updater: Updater) {
    when {
        args.contentEquals(arrayOf("--help")) -> {
            println("GT New Horizons Updater")
            println("Run gtnh-updater without arguments to open the Prism/MultiMC version browser.")
            println("Usage: gtnh-updater source <version>")
            println("       gtnh-updater prepare <instance-directory> <archive.zip>")
            println("Examples: 2.9.2, 2.9.0-RC-2, daily-2026-11-24+855")
        }

        args.size == 2 && args[0] == "source" -> {
            val version = updater.resolveVersion(args[1])
            if (version == null) {
                println("Unsupported GTNH version ID. See gtnh-updater --help.")
                exitProcess(INVALID_ARGUMENTS_EXIT_CODE)
            }
            println("${version.id} (${version.channel.label})")
            println(version.sourceUrl)
        }

        args.size == 3 && args[0] == "prepare" -> {
            try {
                val plan = runBlocking { updater.prepareInstance(args[1].toPath(), args[2].toPath()) }
                println("Candidate prepared: ${plan.candidate}")
            } catch (error: Exception) {
                println("Preparation failed: ${error.message}")
                exitProcess(PREPARATION_FAILURE_EXIT_CODE)
            }
        }

        else -> {
            println("Invalid arguments. See gtnh-updater --help.")
            exitProcess(INVALID_ARGUMENTS_EXIT_CODE)
        }
    }
}
