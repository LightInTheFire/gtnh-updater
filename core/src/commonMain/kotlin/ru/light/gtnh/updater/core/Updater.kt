package ru.light.gtnh.updater.core

import okio.Path
import ru.light.gtnh.updater.core.catalog.CatalogStore
import ru.light.gtnh.updater.core.config.ConfigChoice
import ru.light.gtnh.updater.core.config.ConfigComparisonProgress
import ru.light.gtnh.updater.core.config.ConfigMigration
import ru.light.gtnh.updater.core.config.ConfigReview
import ru.light.gtnh.updater.core.config.ExtraMods
import ru.light.gtnh.updater.core.config.MigrationProgress
import ru.light.gtnh.updater.core.config.MigrationResult
import ru.light.gtnh.updater.core.diagnostics.DiagnosticLog
import ru.light.gtnh.updater.core.download.ArchiveDownloader
import ru.light.gtnh.updater.core.instance.InstanceFinder
import ru.light.gtnh.updater.core.instance.CandidateRegistration
import ru.light.gtnh.updater.core.instance.InstanceCopies
import ru.light.gtnh.updater.core.instance.InstancePreparer
import ru.light.gtnh.updater.core.instance.InstanceSwitch
import ru.light.gtnh.updater.core.instance.PrismTestLauncher
import ru.light.gtnh.updater.core.instance.UpdateSessionStore
import ru.light.gtnh.updater.core.model.AppSettings
import ru.light.gtnh.updater.core.model.ChannelCatalog
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.DownloadedArchive
import ru.light.gtnh.updater.core.model.InstalledInstance
import ru.light.gtnh.updater.core.model.InstanceCopy
import ru.light.gtnh.updater.core.model.PackChannel
import ru.light.gtnh.updater.core.model.PackVersion
import ru.light.gtnh.updater.core.model.PreparationProgress
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.PrismProfile
import ru.light.gtnh.updater.core.model.TestedInstance
import ru.light.gtnh.updater.core.model.TransferProgress
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage
import ru.light.gtnh.updater.core.settings.SettingsStore
import ru.light.gtnh.updater.core.platform.appHomeDirectory

/** Operations shared by interactive and command-line frontends. */
class Updater internal constructor(
    appVersion: String?,
    home: Path?,
    private val launcher: PrismTestLauncher,
    private val settings: SettingsStore,
) {
    constructor(appVersion: String? = null) : this(appVersion, appHomeDirectory(), PrismTestLauncher(), SettingsStore())

    private val diagnostics = DiagnosticLog(appVersion, home = home)
    private val catalogs = CatalogStore()
    private val downloader = ArchiveDownloader()
    private val finder = InstanceFinder()
    private val preparer = InstancePreparer()
    private val config = ConfigMigration(home = home)
    private val updateSessions = UpdateSessionStore(home = home)
    private val registration = CandidateRegistration(home, updateSessions, launcher)
    private val switching = InstanceSwitch(home = home, updates = updateSessions)
    private val copies = InstanceCopies()

    val instances: InstanceBrowser get() = finder

    fun loadSettings(): AppSettings? = settings.load()

    fun saveSettings(values: AppSettings) = diagnostics.track("settings.save") { settings.save(values) }

    fun migrateLegacyCatalogs() = catalogs.migrateLegacy()

    fun loadCatalog(channel: PackChannel): ChannelCatalog = catalogs.load(channel)

    suspend fun refreshCatalog(channel: PackChannel, githubToken: String? = null): ChannelCatalog =
        diagnostics.track("catalog.refresh.${channel.name}") { catalogs.refresh(channel, githubToken) }

    fun resolveVersion(id: String): PackVersion? =
        PackChannel.entries.firstNotNullOfOrNull { channel ->
            catalogs.load(channel).versions.firstOrNull { it.id.equals(id, ignoreCase = true) }
        } ?: PackVersion.parse(id)

    suspend fun downloadArchive(archive: ClientArchive, onProgress: (TransferProgress) -> Unit = {}): DownloadedArchive =
        diagnostics.track("archive.download") { downloader.download(archive, onProgress) }

    suspend fun prepareInstance(
        installed: Path,
        archive: Path,
        targetVersion: String? = null,
        onProgress: (PreparationProgress) -> Unit = {},
    ): PreparedInstance = diagnostics.track("instance.prepare") {
        preparer.prepare(installed, archive, targetVersion, onProgress)
    }

    fun prismProfileFor(instance: Path): PrismProfile? = finder.prismProfileFor(instance)

    fun installationDirectories(): Set<Path> = finder.instanceDirectories()

    suspend fun installClient(
        archive: Path,
        instancesDirectory: Path,
        targetVersion: String,
        onProgress: (PreparationProgress) -> Unit = {},
    ): InstalledInstance = diagnostics.track("instance.install") {
        preparer.install(archive, instancesDirectory, targetVersion, settings.load()?.removeDefaultServerList == true, onProgress)
    }

    fun resumeUpdate(): UpdateSession? = diagnostics.track("update.resume") {
        registration.recover()
        switching.recover()
        updateSessions.load()?.takeIf { it.stage.canResume }?.also { session ->
            config.recover(session.prepared)
            if (session.stage != UpdateStage.PREPARED) runCatching { launcher.labelCandidate(session.prepared) }
        }
    }

    fun lastSwitch(): UpdateSession? = diagnostics.track("update.last-switch") { switching.lastSwitch() }

    fun listInstanceCopies(): List<InstanceCopy> {
        val (roots, protected) = copyLocations()
        return copies.list(roots, protected)
    }

    fun deleteInstanceCopy(path: Path) {
        val (roots, protected) = copyLocations()
        copies.delete(path, roots, protected)
    }

    private fun copyLocations(): Pair<Set<Path>, Map<Path, String>> {
        registration.recover()
        val last = switching.lastSwitch(validateInstances = false)
        val active = updateSessions.load(validateInstances = false)
        val roots = finder.instanceDirectories() + listOfNotNull(active?.prepared?.source?.parent, last?.prepared?.source?.parent)
        val protected = buildMap {
            if (active != null) {
                put(active.prepared.source, "This is the active instance.")
                if (active.stage.canResume) put(active.prepared.candidate, "This copy belongs to the current update.")
            }
            if (last != null) {
                put(last.prepared.source, "This is the active instance.")
                if (last.stage == UpdateStage.ACTIVATED) put(last.prepared.candidate, "This copy is needed for rollback.")
            }
        }
        return roots to protected
    }

    fun activateUpdate(session: UpdateSession): UpdateSession =
        diagnostics.track("instance.activate") { switching.activate(session) }

    fun rollbackUpdate(session: UpdateSession): UpdateSession =
        diagnostics.track("instance.rollback") { switching.rollback(session) }

    fun hasMigrated(session: UpdateSession): Boolean = switching.isMigrated(session)

    fun beginUpdate(prepared: PreparedInstance): UpdateSession {
        registration.recover()
        switching.recover()
        return saveUpdate(prepared, UpdateStage.PREPARED)
    }

    private fun saveUpdate(prepared: PreparedInstance, stage: UpdateStage): UpdateSession =
        UpdateSession(prepared, stage).also(updateSessions::save)

    private fun requireCurrent(prepared: PreparedInstance, stage: UpdateStage) {
        val saved = updateSessions.load()
        check(saved?.prepared == prepared && saved.stage == stage) { "This update session is no longer current." }
    }

    fun extraModsPending(tested: TestedInstance): Boolean =
        settings.load()?.transferExtraMods == true && !config.hasTransferredMods(tested.prepared)

    suspend fun reviewExtraMods(tested: TestedInstance): ExtraMods {
        requireCurrent(tested.prepared, UpdateStage.TEST_CONFIRMED)
        return config.reviewExtraMods(tested.prepared)
    }

    suspend fun transferExtraMods(tested: TestedInstance, review: ExtraMods, selected: Set<String>): Int {
        requireCurrent(tested.prepared, UpdateStage.TEST_CONFIRMED)
        return config.transferExtraMods(tested.prepared, review, selected)
    }

    suspend fun reviewConfigs(
        tested: TestedInstance,
        onProgress: (ConfigComparisonProgress) -> Unit = {},
    ): ConfigReview {
        requireCurrent(tested.prepared, UpdateStage.TEST_CONFIRMED)
        check(!extraModsPending(tested)) { "Complete or skip the extra mods step before reviewing configs." }
        return diagnostics.track("config.compare") { config.review(tested.prepared, onProgress = onProgress) }
    }

    suspend fun migrateInstance(
        tested: TestedInstance,
        review: ConfigReview,
        choices: Map<String, ConfigChoice>,
        ignoredPlayerFolders: Set<String>? = null,
        includeMinecraftSettings: Boolean = true,
        onProgress: (MigrationProgress) -> Unit = {},
    ): MigrationResult = diagnostics.track("instance.migrate") {
        requireCurrent(tested.prepared, UpdateStage.TEST_CONFIRMED)
        config.apply(
            tested.prepared, review, choices, migratePlayerData = true,
            ignoredPlayerFolders, includeMinecraftSettings, onProgress,
        )
    }

    fun registerPrismTest(session: UpdateSession, prism: PrismProfile): UpdateSession {
        registration.recover()
        requireCurrent(session.prepared, session.stage)
        if (session.stage != UpdateStage.PREPARED) return session
        return registration.register(session.prepared, prism.instances, UpdateStage.REGISTERED)
    }

    fun selectNewConfigs(session: UpdateSession): UpdateSession {
        registration.recover()
        requireCurrent(session.prepared, session.stage)
        check(session.stage in setOf(UpdateStage.PREPARED, UpdateStage.REGISTERED)) {
            "Choose new configs before launching the test."
        }
        val removeServerList = settings.load()?.removeDefaultServerList == true
        if (session.stage == UpdateStage.REGISTERED) {
            if (removeServerList) launcher.removeDefaultServerList(session.prepared)
            return saveUpdate(session.prepared, UpdateStage.NEW_CONFIGS_SELECTED)
        }
        val instances = checkNotNull(session.prepared.source.parent)
        return registration.register(session.prepared, instances, UpdateStage.NEW_CONFIGS_SELECTED) {
            if (removeServerList) launcher.removeDefaultServerList(it)
        }
    }

    suspend fun reviewPlayerData(session: UpdateSession): ConfigReview {
        requireCurrent(session.prepared, session.stage)
        check(session.stage == UpdateStage.NEW_CONFIGS_SELECTED) { "Choose new configs first." }
        return config.review(session.prepared, keepNewConfigs = true)
    }

    suspend fun migratePlayerData(
        session: UpdateSession,
        review: ConfigReview,
        ignoredPlayerFolders: Set<String>,
        includeMinecraftSettings: Boolean = false,
        onProgress: (MigrationProgress) -> Unit,
    ): MigrationResult = diagnostics.track("instance.migrate") {
        requireCurrent(session.prepared, session.stage)
        check(session.stage == UpdateStage.NEW_CONFIGS_SELECTED && review.files.isEmpty()
        ) { "This update is unavailable for migration with new configs." }
        config.apply(
            session.prepared, review, emptyMap(), migratePlayerData = true,
            ignoredPlayerFolders, includeMinecraftSettings, onProgress,
        )
    }

    fun launchPrismTest(session: UpdateSession, prism: PrismProfile): UpdateSession {
        requireCurrent(session.prepared, session.stage)
        val migrated = hasMigrated(session)
        check(session.stage in setOf(
            UpdateStage.REGISTERED, UpdateStage.TEST_STARTED, UpdateStage.TEST_CONFIRMED,
            UpdateStage.MIGRATED_TEST_STARTED, UpdateStage.MIGRATED_TEST_CONFIRMED,
        ) || (session.stage == UpdateStage.NEW_CONFIGS_SELECTED && migrated)) {
            "This candidate is unavailable for a test launch."
        }
        val stage = if (migrated) UpdateStage.MIGRATED_TEST_STARTED else UpdateStage.TEST_STARTED
        diagnostics.track("prism.test-launch") {
            launcher.launch(session.prepared, prism, settings.load()?.removeDefaultServerList == true)
        }
        return saveUpdate(session.prepared, stage)
    }

    /** The caller confirms that Minecraft reached its main menu and was then closed. */
    fun confirmTestClosed(session: UpdateSession): UpdateSession {
        requireCurrent(session.prepared, session.stage)
        val stage = when (session.stage) {
            UpdateStage.TEST_STARTED -> UpdateStage.TEST_CONFIRMED
            UpdateStage.MIGRATED_TEST_STARTED -> UpdateStage.MIGRATED_TEST_CONFIRMED
            else -> error("Launch the test before confirming it.")
        }
        return saveUpdate(session.prepared, stage)
    }
}
