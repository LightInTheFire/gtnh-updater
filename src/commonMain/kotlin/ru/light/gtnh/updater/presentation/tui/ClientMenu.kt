package ru.light.gtnh.updater.presentation.tui

import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.terminal.Terminal
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.catalog.searchVersions
import ru.light.gtnh.updater.core.model.AppSettings
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.model.PackVersion
import ru.light.gtnh.updater.core.model.UpdateStage

private const val KEY_REFRESH = "F5"
private const val KEY_SETTINGS = "F2"
private const val KEY_RESUME = "F6"
private const val VERSION_LIST_HEADER_ROWS = 4
private const val MAX_QUERY_LENGTH = 120
private const val CATALOG_REFRESH_FAILED = "Refresh unavailable | Showing existing catalog"

internal fun runClientMenu(updater: Updater) {
    val terminal = Terminal()
    if (!terminal.terminalInfo.interactive) {
        println("The version browser needs an interactive terminal. Run gtnh-updater --help for CLI usage.")
        return
    }
    val raw = terminal.enterRawModeOrNull()
    if (raw == null) {
        println("Could not open the terminal browser. Run gtnh-updater --help for CLI usage.")
        return
    }

    raw.use { rawInput ->
        val input = TerminalInput(rawInput)
        var refresh: BackgroundOperation<ChannelCatalog>? = null
        val startupRefreshes = mutableMapOf<PackChannel, BackgroundOperation<ChannelCatalog>>()
        try {
            // Request distinct key codes for Escape and Ctrl+Escape on supporting terminals.
            terminal.rawPrint(TERMINAL_ENTER)
            val channels = PackChannel.entries
            updater.migrateLegacyCatalogs()
            val savedSettings = runCatching { updater.loadSettings() }
            var settings = savedSettings.getOrNull() ?: AppSettings()
            fun startRefresh(channel: PackChannel): BackgroundOperation<ChannelCatalog> {
                val token = settings.githubToken
                return BackgroundOperation { updater.refreshCatalog(channel, token) }
            }

            fun applySavedSettings(updated: AppSettings) {
                val tokenChanged = updated.githubToken != settings.githubToken
                settings = updated
                if (tokenChanged) {
                    startupRefreshes.remove(PackChannel.DAILY)?.let {
                        it.cancel()
                        startupRefreshes[PackChannel.DAILY] = startRefresh(PackChannel.DAILY)
                    }
                }
            }

            if (savedSettings.getOrNull() == null) {
                channels.forEach { startupRefreshes[it] = startRefresh(it) }
                when (val result = runSettingsScreen(
                    terminal, input, updater::saveSettings, settings, firstRun = true, loadFailed = savedSettings.isFailure,
                    catalogStatus = {
                        val outcomes = startupRefreshes.values.mapNotNull { it.outcome() }
                        val ready = outcomes.count { it.isSuccess }
                        val count = startupRefreshes.size
                        if (outcomes.size < count) "Loading catalogs ($ready/$count ready)..."
                        else "Catalogs ready: $ready/$count."
                    },
                )) {
                    is SettingsSelection.Saved -> applySavedSettings(result.settings)
                    SettingsSelection.Back -> Unit
                    SettingsSelection.Quit -> return
                }
            }

            fun refreshChannel(channel: PackChannel): BackgroundOperation<ChannelCatalog> {
                val pending = startupRefreshes[channel]
                if (pending != null && pending.outcome()?.isFailure != true) return pending
                startupRefreshes.remove(channel)?.cancel()
                return startRefresh(channel)
            }
            var catalog = ChannelCatalog(emptyList(), status = "")
            var channelCursor = 0
            var activeChannel: PackChannel? = null
            var query = ""
            var matches = emptyList<PackVersion>()
            var cursor = 0
            var offset = 0
            var confirmed: PackVersion? = null
            var archiveCursor = 0
            var selectedArchive: ClientArchive? = null
            var previousSize = 0 to 0
            var redraw = true
            var savedUpdate = runCatching { updater.resumeUpdate() }
            var lastSwitch = runCatching { updater.lastSwitch() }
            fun refreshSavedUpdates() {
                savedUpdate = runCatching { updater.resumeUpdate() }
                lastSwitch = runCatching { updater.lastSwitch() }
            }

            while (true) {
                val completedRefresh = refresh
                completedRefresh?.outcome()?.let { outcome ->
                    activeChannel?.let { channel ->
                        if (startupRefreshes[channel] === completedRefresh) startupRefreshes.remove(channel)
                    }
                    refresh = null
                    val selectedId = matches.getOrNull(cursor)?.id
                    outcome.fold(
                        onSuccess = { updated ->
                            catalog = updated
                            matches = searchVersions(catalog.versions, query)
                            cursor = matches.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

                            confirmed = catalog.versions.firstOrNull { it.id == confirmed?.id }
                            val archives = catalog.archives[confirmed?.id].orEmpty()
                            archiveCursor = archiveCursor.coerceAtMost(archives.lastIndex.coerceAtLeast(0))
                            selectedArchive = selectedArchive?.takeIf { it in archives }
                        },
                        onFailure = { error ->
                            val detail = error.message?.filter { it in ' '..'~' } ?: error::class.simpleName
                            catalog = catalog.copy(status = "$CATALOG_REFRESH_FAILED ($detail)")
                        },
                    )
                    redraw = true
                }

                terminal.updateSize()
                val size = terminal.size
                val rowCount = (size.height - VERSION_LIST_HEADER_ROWS - VERSION_LIST_FOOTER_ROWS - TERMINAL_EDGE_MARGIN).coerceAtLeast(1)
                if (cursor < offset) offset = cursor
                if (cursor >= offset + rowCount) offset = cursor - rowCount + 1

                val dimensions = size.width to size.height
                if (redraw || dimensions != previousSize) {
                    val channel = activeChannel
                    if (channel == null) {
                        val recoveryErrors = listOfNotNull(
                            savedUpdate.exceptionOrNull()?.let { "Resume failed: ${it.message ?: "Unknown error."}" },
                            lastSwitch.exceptionOrNull()?.let { "Rollback unavailable: ${it.message ?: "Unknown error."}" },
                        )
                        renderChannels(
                            terminal, channels, channelCursor, savedUpdate.getOrNull() != null,
                            lastSwitch.getOrNull()?.stage == UpdateStage.ACTIVATED, recoveryErrors,
                        )
                    } else {
                        renderBrowser(
                            terminal, channel, query, matches, cursor, offset, rowCount,
                            catalog, refresh != null, confirmed, archiveCursor, selectedArchive,
                        )
                    }
                    previousSize = dimensions
                    redraw = false
                }

                val key = input.readKeyOrNull() ?: continue
                if (key.requestsExit()) break
                if (key.ctrl || key.alt) continue
                val channel = activeChannel
                if (channel == null) {
                    when (key.key) {
                        KEY_ESCAPE -> break
                        KEY_UP -> channelCursor = (channelCursor - 1).coerceAtLeast(0)
                        KEY_DOWN -> channelCursor = (channelCursor + 1).coerceAtMost(channels.lastIndex)
                        KEY_COPIES -> if (manageCopies(terminal, input, updater)) return
                        KEY_RESUME -> savedUpdate.getOrNull()?.let { session ->
                            if (resumePreparationScreen(terminal, input, updater, session)) return
                            refreshSavedUpdates()
                        }
                        KEY_ROLLBACK -> lastSwitch.getOrNull()?.takeIf { it.stage == UpdateStage.ACTIVATED }?.let { session ->
                            if (resumePreparationScreen(terminal, input, updater, session, askRollback = true)) return
                            refreshSavedUpdates()
                        }
                        KEY_SETTINGS -> when (val result = runSettingsScreen(terminal, input, updater::saveSettings, settings)) {
                            is SettingsSelection.Saved -> applySavedSettings(result.settings)
                            SettingsSelection.Back -> Unit
                            SettingsSelection.Quit -> break
                        }
                        KEY_ENTER, KEY_RIGHT -> {
                            val selected = channels[channelCursor]
                            activeChannel = selected
                            catalog = updater.loadCatalog(selected)
                            refresh = refreshChannel(selected)
                            matches = catalog.versions
                            query = ""
                            cursor = 0
                            offset = 0
                        }
                    }
                    redraw = true
                    continue
                }
                if (confirmed != null) {
                    val archives = catalog.archives[confirmed.id].orEmpty()
                    when (key.key) {
                        KEY_ESCAPE, KEY_LEFT -> if (selectedArchive == null) confirmed = null else selectedArchive = null
                        KEY_UP -> if (selectedArchive == null) archiveCursor = (archiveCursor - 1).coerceAtLeast(0)
                        KEY_DOWN -> if (selectedArchive == null) {
                            archiveCursor = (archiveCursor + 1).coerceAtMost(archives.lastIndex.coerceAtLeast(0))
                        }
                        KEY_ENTER, KEY_RIGHT -> {
                            val archive = selectedArchive
                            if (archive == null) {
                                selectedArchive = archives.getOrNull(archiveCursor)
                            } else {
                                when (runDownloadScreen(terminal, input, archive, confirmed.id, updater)) {
                                    UpdateFlowExit.QUIT -> break
                                    UpdateFlowExit.MENU -> {
                                        if (refresh !== startupRefreshes[channel]) refresh?.cancel()
                                        refresh = null
                                        selectedArchive = null
                                        confirmed = null
                                        activeChannel = null
                                    }
                                    UpdateFlowExit.BACK -> Unit
                                }
                                refreshSavedUpdates()
                            }
                        }
                    }
                    redraw = true
                    continue
                }

                val oldQuery = query
                when (key.key) {
                    KEY_ESCAPE, KEY_LEFT -> {
                        if (refresh !== startupRefreshes[channel]) refresh?.cancel()
                        refresh = null
                        activeChannel = null
                    }
                    KEY_UP -> cursor = (cursor - 1).coerceAtLeast(0)
                    KEY_DOWN -> cursor = (cursor + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                    KEY_ENTER, KEY_RIGHT -> {
                        confirmed = matches.getOrNull(cursor)
                        archiveCursor = 0
                    }
                    KEY_REFRESH -> if (refresh == null) {
                        refresh = refreshChannel(channel)
                    }
                    KEY_BACKSPACE -> query = query.dropLast(1)
                    else -> if (key.key.length == 1 && key.key[0] in ' '..'~' && query.length < MAX_QUERY_LENGTH) {
                        query += key.key
                    }
                }
                if (query != oldQuery) {
                    matches = searchVersions(catalog.versions, query)
                    cursor = 0
                    offset = 0
                }
                redraw = true
            }
        } finally {
            refresh?.cancel()
            startupRefreshes.values.forEach { it.cancel() }
            terminal.rawPrint(TERMINAL_EXIT)
        }
    }
}
