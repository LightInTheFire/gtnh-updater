package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.terminal.Terminal
import ru.light.gtnh.updater.core.model.AppSettings
import ru.light.gtnh.updater.core.model.MAX_GITHUB_TOKEN_LENGTH

private const val KEY_SPACE = " "
private const val REMOVE_DEFAULT_SERVER_LIST_LABEL = "Remove DefaultServerList"
private const val TRANSFER_EXTRA_MODS_LABEL = "Transfer extra mods"
private const val SETTINGS_SAVE_ERROR = "Could not save settings. Check access to the user settings directory."
private const val SETTINGS_LOAD_ERROR = "Could not read the saved settings. Review the values before saving."

private enum class SettingsRow(val description: String) {
    GITHUB_TOKEN(
        "Optional. Authenticates requests to the GitHub Daily releases API to increase its rate limit. " +
            "Public lookup works without a token. The token is hidden in the TUI and stored in your local settings file.",
    ),
    REMOVE_DEFAULT_SERVER_LIST(
        "On removes DefaultServerList from the new instance before its Prism test launch. " +
            "Off keeps the mod as shipped with the target pack.",
    ),
    TRANSFER_EXTRA_MODS(
        "On enables extra mod selection before config review. Mods found outside a known pack inventory are preselected. " +
            "If the inventory is missing, select the mods you added. Off keeps the target pack's mods.",
    ),
    SAVE(
        "Writes your choices locally and returns to the channel menu. The token is used on the next Daily " +
            "catalog request. These settings are saved on this device.",
    ),
}

internal sealed interface SettingsSelection {
    data class Saved(val settings: AppSettings) : SettingsSelection
    data object Back : SettingsSelection
    data object Quit : SettingsSelection
}

internal fun runSettingsScreen(
    terminal: Terminal,
    input: TerminalInput,
    saveSettings: (AppSettings) -> Unit,
    current: AppSettings,
    firstRun: Boolean = false,
    loadFailed: Boolean = false,
    catalogStatus: (() -> String)? = null,
): SettingsSelection {
    val fields = SettingsRow.entries
    var settings = current
    var selection = 0
    var editedToken: StringBuilder? = null
    var failure = if (loadFailed) SETTINGS_LOAD_ERROR else null
    var redraw = true
    var previousSize = 0 to 0
    var previousCatalogStatus: String? = null

    fun save(values: AppSettings): SettingsSelection.Saved? = try {
        saveSettings(values)
        SettingsSelection.Saved(values)
    } catch (_: Exception) {
        failure = SETTINGS_SAVE_ERROR
        null
    }

    while (true) {
        val size = terminal.updateSize()
        val dimensions = size.width to size.height
        val edit = editedToken
        val status = catalogStatus?.invoke()
        if (redraw || dimensions != previousSize || status != previousCatalogStatus) {
            val rows = buildList {
                add((if (firstRun) " GTNH UPDATER  /  First launch setup" else " GTNH UPDATER  /  Settings") to HIGHLIGHT)
                add("" to "")
                if (edit != null) {
                    add(" GitHub token (optional)" to SECTION_STYLE)
                    add(" Paste a replacement token or leave empty to clear it." to "")
                    val preview = if (edit.isEmpty()) "(empty)" else "(hidden)"
                    add(" Token > $preview" to SECTION_STYLE)
                } else {
                    for ((index, field) in fields.withIndex()) {
                        val text = when (field) {
                            SettingsRow.GITHUB_TOKEN -> "GitHub token: ${if (settings.githubToken != null) "Set" else "Not set"}"
                            SettingsRow.REMOVE_DEFAULT_SERVER_LIST ->
                                "$REMOVE_DEFAULT_SERVER_LIST_LABEL: ${settings.removeDefaultServerList.switchLabel()}"
                            SettingsRow.TRANSFER_EXTRA_MODS ->
                                "$TRANSFER_EXTRA_MODS_LABEL: ${settings.transferExtraMods.switchLabel()}"
                            SettingsRow.SAVE -> "Save settings"
                        }
                        val marker = if (index == selection) ">" else " "
                        add(" $marker  $text" to if (index == selection) HIGHLIGHT else "")
                    }
                    add("" to "")
                    val descriptionWidth = (size.width - TERMINAL_EDGE_MARGIN - 1).coerceAtLeast(1)
                    wrapSettingsDescription(fields[selection].description, descriptionWidth)
                        .forEach { add(" $it" to "") }
                    failure?.let { add(" $it" to SECTION_STYLE) }
                }
                status?.let { add(" $it" to "") }
            }
            val footer = when {
                edit != null -> "Enter: Apply token   Left/Esc: Cancel"
                firstRun && failure == null -> "Up/Down: Move   Enter: Edit/save   Esc: Use defaults"
                else -> "Up/Down: Move   Enter: Edit/save   Left/Esc: Back"
            }
            renderScreen(terminal, rows, footer)
            redraw = false
            previousSize = dimensions
            previousCatalogStatus = status
        }

        val key = input.readKeyOrNull() ?: continue
        if (key.requestsExit()) return SettingsSelection.Quit
        if (key.ctrl || key.alt) continue
        if (edit != null) {
            when (key.key) {
                KEY_ESCAPE, KEY_LEFT -> editedToken = null
                KEY_ENTER -> {
                    settings = settings.copy(githubToken = edit.toString().ifEmpty { null })
                    editedToken = null
                }
                KEY_BACKSPACE -> if (edit.isNotEmpty()) edit.deleteAt(edit.lastIndex)
                else -> if (edit.length < MAX_GITHUB_TOKEN_LENGTH && key.key.length == 1 && key.key.single() in '!'..'~') {
                    edit.append(key.key)
                }
            }
        } else {
            when (key.key) {
                KEY_ESCAPE, KEY_LEFT -> {
                    if (firstRun && failure == null) {
                        save(current)?.let { return it }
                    } else {
                        return SettingsSelection.Back
                    }
                }
                KEY_UP -> selection = (selection - 1).coerceAtLeast(0)
                KEY_DOWN -> selection = (selection + 1).coerceAtMost(fields.lastIndex)
                KEY_ENTER, KEY_RIGHT, KEY_SPACE -> when (fields[selection]) {
                    SettingsRow.GITHUB_TOKEN -> editedToken = StringBuilder()
                    SettingsRow.REMOVE_DEFAULT_SERVER_LIST ->
                        settings = settings.copy(removeDefaultServerList = !settings.removeDefaultServerList)
                    SettingsRow.TRANSFER_EXTRA_MODS -> settings = settings.copy(transferExtraMods = !settings.transferExtraMods)
                    SettingsRow.SAVE -> save(settings)?.let { return it }
                }
            }
        }
        redraw = true
    }
}

private fun Boolean.switchLabel(): String = if (this) "On" else "Off"

private fun wrapSettingsDescription(description: String, width: Int): List<String> = buildList {
    var line = ""
    for (word in description.split(' ')) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) {
            add(line)
            line = ""
        }
        line = if (line.isEmpty()) word else "$line $word"
    }
    if (line.isNotEmpty()) add(line)
}
