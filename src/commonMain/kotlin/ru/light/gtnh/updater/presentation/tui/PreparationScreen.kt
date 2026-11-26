package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import okio.Path
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.model.PreparationProgress
import ru.light.gtnh.updater.core.model.PrismProfile
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage

private const val KEY_CONFIRM_TEST = "F4"
private const val KEY_ACTIVATE = "F7"
internal const val KEY_ROLLBACK = "F8"
private const val KEY_CONFIRM_SWITCH = "y"
private const val TEST_INSTRUCTION_STYLE = "1;33"

internal enum class UpdateFlowExit { BACK, MENU, QUIT }
private enum class PreparationScreenExit { BACK, MENU, QUIT, CHOOSE_AGAIN }

internal fun runPreparationScreen(
    terminal: Terminal,
    input: TerminalInput,
    archive: Path,
    targetVersion: String,
    updater: Updater,
): UpdateFlowExit {
    while (true) {
        val selection = pickInstance(terminal, { input.readKeyOrNull() }, targetVersion, updater.instances)
        when (selection) {
            InstanceSelection.Back -> return UpdateFlowExit.BACK
            InstanceSelection.Quit -> return UpdateFlowExit.QUIT
            is InstanceSelection.Selected -> when (
                runPreparationProgress(
                    terminal, input, targetVersion, updater, updater.prismProfileFor(selection.path),
                    prepare = { progress -> updater.beginUpdate(updater.prepareInstance(selection.path, archive, targetVersion, progress)) },
                )
            ) {
                PreparationScreenExit.BACK -> return UpdateFlowExit.BACK
                PreparationScreenExit.MENU -> return UpdateFlowExit.MENU
                PreparationScreenExit.QUIT -> return UpdateFlowExit.QUIT
                PreparationScreenExit.CHOOSE_AGAIN -> continue
            }
        }
    }
}

internal fun resumePreparationScreen(
    terminal: Terminal,
    input: TerminalInput,
    updater: Updater,
    session: UpdateSession,
    askRollback: Boolean = false,
): Boolean = runPreparationProgress(
    terminal, input, session.prepared.targetVersion ?: "Unknown", updater, updater.prismProfileFor(session.prepared.source),
    restored = session,
    askRollback = askRollback,
) == PreparationScreenExit.QUIT

private fun runPreparationProgress(
    terminal: Terminal,
    input: TerminalInput,
    targetVersion: String,
    updater: Updater,
    prism: PrismProfile?,
    restored: UpdateSession? = null,
    askRollback: Boolean = false,
    prepare: (suspend ((PreparationProgress) -> Unit) -> UpdateSession)? = null,
): PreparationScreenExit {
    val progress = atomic(PreparationProgress())
    val preparation = prepare?.let { operation ->
        BackgroundOperation { operation { progress.value = it } }
    }
    var session = restored
    var inspected: UpdateSession? = null
    var migrated = false
    var modsPending = false
    var confirmSwitch = askRollback
    var launchStatus: String? = null
    var cancelling = false
    var exitRequested = false
    var previousFrame = emptyList<Pair<String, String>>()
    var previousSize = 0 to 0
    var previousFooter = ""

    fun continueCleanUpdate(confirmed: UpdateSession): PreparationScreenExit? {
        val tested = confirmed.tested ?: return null
        if (updater.hasMigrated(confirmed)) return null
        inspected = null
        previousFrame = emptyList()
        if (updater.extraModsPending(tested)) {
            if (transferExtraMods(terminal, input, updater, tested)) return PreparationScreenExit.QUIT
            if (updater.extraModsPending(tested)) return null
        }
        return when (reviewConfigs(terminal, input, updater, tested)) {
            UpdateFlowExit.BACK -> null
            UpdateFlowExit.MENU -> PreparationScreenExit.MENU
            UpdateFlowExit.QUIT -> PreparationScreenExit.QUIT
        }
    }

    try {
        if (restored != null) continueCleanUpdate(restored)?.let { return it }
        while (true) {
            val outcome = restored?.let { Result.success(it) } ?: preparation?.outcome()
            if (session == null) session = outcome?.getOrNull()
            val active = session
            val stage = active?.stage
            if (active !== inspected) {
                inspected = active
                migrated = active?.takeIf {
                    stage == UpdateStage.TEST_CONFIRMED || stage == UpdateStage.MIGRATED_TEST_STARTED ||
                        stage == UpdateStage.MIGRATED_TEST_CONFIRMED
                }?.let(updater::hasMigrated) == true
                modsPending = !migrated && active?.tested?.let(updater::extraModsPending) == true
            }
            val testing = !migrated && (stage == UpdateStage.TEST_STARTED || stage == UpdateStage.MIGRATED_TEST_STARTED)
            if (exitRequested && outcome != null) return PreparationScreenExit.QUIT

            val size = terminal.updateSize()
            val contentWidth = (size.width - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
            val rows = buildList {
                add(" GTNH UPDATER  /  Prepare update" to HIGHLIGHT)
                add(" Target: $targetVersion" to SECTION_STYLE)
                add("" to "")
                when {
                    outcome == null -> {
                        add((if (cancelling) " Cancelling preparation..." else " Extracting client ZIP...") to SECTION_STYLE)
                        val current = progress.value
                        if (current.totalEntries > 0) {
                            add(" ${current.extractedEntries} / ${current.totalEntries} entries" to "")
                        }
                    }

                    else -> {
                        outcome.fold(
                            onSuccess = { started ->
                                val prepared = active?.prepared ?: started.prepared
                                val status = when (stage) {
                                    UpdateStage.ACTIVATED -> " Update activated"
                                    UpdateStage.ROLLED_BACK -> " Previous version restored"
                                    else -> " Candidate prepared"
                                }
                                add(status to SECTION_STYLE)
                                val liveVersion = if (stage == UpdateStage.ACTIVATED) prepared.targetVersion else prepared.currentVersion
                                add(" Active version: ${liveVersion?.terminalText() ?: "Unknown"}" to "")
                                val instructions = when {
                                    confirmSwitch -> " Quit Minecraft and fully exit Prism before confirming the switch."
                                    stage == UpdateStage.ACTIVATED -> " Reopen Prism and select the updated entry. The old copy is marked [Backup ...]."
                                    stage == UpdateStage.ROLLED_BACK -> " The newer copy, including its worlds, is preserved below."
                                    testing -> " Reach the Minecraft main menu, quit the game, then press F4."
                                    migrated -> " Migration complete. Activate the update at the original path."
                                    modsPending -> " Test confirmed. Copy extra mods or skip this step before choosing configs."
                                    prism == null -> " Config review requires a Prism test launch."
                                    stage == UpdateStage.TEST_CONFIRMED -> " Test confirmed. Migration copies player data and applies config choices."
                                    else -> " Test launch the candidate before reviewing configs."
                                }
                                add(instructions to if (testing || confirmSwitch) TEST_INSTRUCTION_STYLE else "")
                                if (confirmSwitch) {
                                    add(" An open launcher can overwrite instance names with old values." to TEST_INSTRUCTION_STYLE)
                                }
                                launchStatus?.let { add(" ${it.terminalText()}" to SECTION_STYLE) }
                                if (confirmSwitch || stage == UpdateStage.ACTIVATED || stage == UpdateStage.ROLLED_BACK) {
                                    add(" Active path: ${prepared.source.toString().terminalText()}" to "")
                                    val label = if (stage == UpdateStage.ACTIVATED || stage == UpdateStage.ROLLED_BACK) "Saved copy" else "Candidate"
                                    add(" $label:" to "")
                                }
                                prepared.candidate.toString().terminalText()
                                    .chunked((contentWidth - TERMINAL_EDGE_MARGIN).coerceAtLeast(1))
                                    .forEach { add(" $it" to "") }
                            },
                            onFailure = { error ->
                                if (cancelling || error is CancellationException) {
                                    add(" Preparation cancelled" to SECTION_STYLE)
                                } else {
                                    add(" Preparation failed" to SECTION_STYLE)
                                    val detail = error.message?.terminalText() ?: error::class.simpleName.orEmpty()
                                    detail.chunked((contentWidth - TERMINAL_EDGE_MARGIN).coerceAtLeast(1))
                                        .forEach { add(" $it" to "") }
                                }
                            },
                        )
                    }
                }
            }
            val footer = when {
                outcome == null && !cancelling -> "Left/Esc: Cancel"
                outcome?.isFailure == true -> "Enter: Choose instance   Left/Esc: Back"
                confirmSwitch -> "Y: Confirm switch | Esc: Cancel"
                stage == UpdateStage.ACTIVATED -> "F8: Roll back | Esc: Back"
                stage == UpdateStage.ROLLED_BACK -> "Esc: Back"
                migrated -> "F7/Enter: Activate update | Esc: Back"
                testing -> "F4: Confirm game closed | Enter: Retry test | Esc: Back"
                modsPending -> "Enter: Transfer extra mods | Esc: Back"
                stage == UpdateStage.TEST_CONFIRMED -> "Enter: Migrate data and configs | Esc: Back"
                outcome?.isSuccess == true && prism != null -> "Enter: Test launch | Esc: Back"
                outcome != null -> "Left/Esc: Back"
                else -> ""
            }
            val dimensions = size.width to size.height
            if (rows != previousFrame || dimensions != previousSize || footer != previousFooter) {
                renderScreen(terminal, rows, footer)
                previousFrame = rows
                previousSize = dimensions
                previousFooter = footer
            }

            val key = input.readKeyOrNull() ?: continue
            if (key.requestsExit()) {
                if (outcome != null) return PreparationScreenExit.QUIT
                exitRequested = true
                cancelling = true
                preparation?.cancel()
                continue
            }
            if (key.ctrl || key.alt) continue
            if (confirmSwitch && active != null) {
                when {
                    key.key == KEY_ESCAPE || key.key == KEY_LEFT -> {
                        if (stage == UpdateStage.ACTIVATED) return PreparationScreenExit.MENU
                        confirmSwitch = false
                    }
                    key.key.equals(KEY_CONFIRM_SWITCH, ignoreCase = true) -> {
                        confirmSwitch = false
                        launchStatus = try {
                            session = if (stage == UpdateStage.ACTIVATED) updater.rollbackUpdate(active) else updater.activateUpdate(active)
                            null
                        } catch (error: Exception) {
                            "Could not switch instances. ${error.message.orEmpty()}"
                        }
                    }
                }
                continue
            }

            if (outcome == null) {
                if (key.key == KEY_ESCAPE || key.key == KEY_LEFT) {
                    cancelling = true
                    preparation?.cancel()
                }
            } else {
                when (key.key) {
                    KEY_ESCAPE, KEY_LEFT -> return if (askRollback || stage == UpdateStage.ACTIVATED || stage == UpdateStage.ROLLED_BACK) {
                        PreparationScreenExit.MENU
                    } else {
                        PreparationScreenExit.BACK
                    }
                    KEY_ACTIVATE -> if (migrated) confirmSwitch = true
                    KEY_ROLLBACK -> if (stage == UpdateStage.ACTIVATED) confirmSwitch = true
                    KEY_CONFIRM_TEST -> if (active != null && testing) {
                        val confirmed = try {
                            updater.confirmTestClosed(active)
                        } catch (error: Exception) {
                            launchStatus = "Could not save test status. ${error.message.orEmpty()}"
                            null
                        }
                        if (confirmed != null) {
                            session = confirmed
                            launchStatus = null
                            continueCleanUpdate(confirmed)?.let { return it }
                        }
                    }
                    KEY_CONFIG_REVIEW, KEY_EXTRA_MODS -> active?.let { continueCleanUpdate(it) }?.let { return it }
                    KEY_ENTER, KEY_RIGHT -> {
                        if (outcome.isFailure) return PreparationScreenExit.CHOOSE_AGAIN
                        if (migrated) {
                            confirmSwitch = true
                        } else if (active != null && stage == UpdateStage.TEST_CONFIRMED) {
                            continueCleanUpdate(active)?.let { return it }
                        } else if (prism != null && active != null && stage in setOf(
                                UpdateStage.PREPARED, UpdateStage.REGISTERED, UpdateStage.TEST_STARTED,
                                UpdateStage.MIGRATED_TEST_STARTED,
                            )
                        ) {
                            launchStatus = try {
                                val registered = updater.registerPrismTest(active, prism)
                                session = registered
                                session = updater.launchPrismTest(registered, prism)
                                "Launch requested in Prism Launcher."
                            } catch (error: Exception) {
                                "Test launch failed. ${error.message.orEmpty()}"
                            }
                        }
                    }
                }
            }
        }
    } finally {
        if (preparation?.outcome() == null) preparation?.cancel()
    }
}
