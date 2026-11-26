package ru.light.gtnh.updater.core

import okio.Path
import ru.light.gtnh.updater.core.catalog.CatalogStore
import ru.light.gtnh.updater.core.config.ConfigChoice
import ru.light.gtnh.updater.core.config.ConfigMigration
import ru.light.gtnh.updater.core.config.ConfigReview
import ru.light.gtnh.updater.core.config.ExtraMods
import ru.light.gtnh.updater.core.config.MigrationResult
import ru.light.gtnh.updater.core.download.ArchiveDownloader
import ru.light.gtnh.updater.core.instance.InstanceFinder
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

/** Operations shared by interactive and command-line frontends. */
class Updater {
    private val settings = SettingsStore()
    private val catalogs = CatalogStore()
    private val downloader = ArchiveDownloader()
    private val finder = InstanceFinder()
    private val preparer = InstancePreparer()
    private val launcher = PrismTestLauncher()
    private val config = ConfigMigration()
    private val updateSessions = UpdateSessionStore()
    private val switching = InstanceSwitch(updates = updateSessions)
    private val copies = InstanceCopies()

    val instances: InstanceBrowser get() = finder

    fun loadSettings(): AppSettings? = settings.load()

    fun saveSettings(values: AppSettings) = settings.save(values)

    fun migrateLegacyCatalogs() = catalogs.migrateLegacy()

    fun loadCatalog(channel: PackChannel): ChannelCatalog = catalogs.load(channel)

    suspend fun refreshCatalog(channel: PackChannel, githubToken: String? = null): ChannelCatalog =
        catalogs.refresh(channel, githubToken)

    fun resolveVersion(id: String): PackVersion? =
        PackChannel.entries.firstNotNullOfOrNull { channel ->
            catalogs.load(channel).versions.firstOrNull { it.id.equals(id, ignoreCase = true) }
        } ?: PackVersion.parse(id)

    suspend fun downloadArchive(archive: ClientArchive, onProgress: (TransferProgress) -> Unit = {}): DownloadedArchive =
        downloader.download(archive, onProgress)

    suspend fun prepareInstance(
        installed: Path,
        archive: Path,
        targetVersion: String? = null,
        onProgress: (PreparationProgress) -> Unit = {},
    ): PreparedInstance = preparer.prepare(installed, archive, targetVersion, onProgress)

    fun prismProfileFor(instance: Path): PrismProfile? = finder.prismProfileFor(instance)

    fun installationDirectories(): Set<Path> = finder.instanceDirectories()

    suspend fun installClient(
        archive: Path,
        instancesDirectory: Path,
        targetVersion: String,
        onProgress: (PreparationProgress) -> Unit = {},
    ): InstalledInstance =
        preparer.install(archive, instancesDirectory, targetVersion, settings.load()?.removeDefaultServerList == true, onProgress)

    fun resumeUpdate(): UpdateSession? {
        switching.recover()
        return updateSessions.load()?.takeIf { it.stage.canResume }?.also { session ->
            config.recover(session.prepared)
            if (session.stage != UpdateStage.PREPARED) runCatching { launcher.labelCandidate(session.prepared) }
        }
    }

    fun lastSwitch(): UpdateSession? = switching.lastSwitch()

    fun listInstanceCopies(): List<InstanceCopy> {
        val (roots, protected) = copyLocations()
        return copies.list(roots, protected)
    }

    fun deleteInstanceCopy(path: Path) {
        val (roots, protected) = copyLocations()
        copies.delete(path, roots, protected)
    }

    private fun copyLocations(): Pair<Set<Path>, Map<Path, String>> {
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

    fun activateUpdate(session: UpdateSession): UpdateSession = switching.activate(session)

    fun rollbackUpdate(session: UpdateSession): UpdateSession = switching.rollback(session)

    fun hasMigrated(session: UpdateSession): Boolean = switching.isMigrated(session)

    fun beginUpdate(prepared: PreparedInstance): UpdateSession {
        switching.recover()
        return saveUpdate(prepared, UpdateStage.PREPARED)
    }

    private fun saveUpdate(prepared: PreparedInstance, stage: UpdateStage): UpdateSession =
        UpdateSession(prepared, stage).also(updateSessions::save)

    fun extraModsPending(tested: TestedInstance): Boolean =
        settings.load()?.transferExtraMods == true && !config.hasTransferredMods(tested.prepared)

    suspend fun reviewExtraMods(tested: TestedInstance): ExtraMods = config.reviewExtraMods(tested.prepared)

    suspend fun transferExtraMods(tested: TestedInstance, review: ExtraMods, selected: Set<String>): Int =
        config.transferExtraMods(tested.prepared, review, selected)

    suspend fun reviewConfigs(tested: TestedInstance): ConfigReview {
        check(!extraModsPending(tested)) { "Complete or skip the extra mods step before reviewing configs." }
        return config.review(tested.prepared)
    }

    suspend fun migrateInstance(
        tested: TestedInstance,
        review: ConfigReview,
        choices: Map<String, ConfigChoice>,
    ): MigrationResult = config.apply(tested.prepared, review, choices, migratePlayerData = true)

    fun registerPrismTest(session: UpdateSession, prism: PrismProfile): UpdateSession {
        if (session.stage != UpdateStage.PREPARED) return session
        val registered = launcher.register(session.prepared, prism) {
            saveUpdate(it, UpdateStage.REGISTERED)
        }
        return UpdateSession(registered, UpdateStage.REGISTERED)
    }

    fun launchPrismTest(session: UpdateSession, prism: PrismProfile): UpdateSession {
        check(session.stage in setOf(
            UpdateStage.REGISTERED, UpdateStage.TEST_STARTED, UpdateStage.TEST_CONFIRMED,
            UpdateStage.MIGRATED_TEST_STARTED, UpdateStage.MIGRATED_TEST_CONFIRMED,
        )) { "This candidate is unavailable for a test launch." }
        val stage = if (hasMigrated(session)) UpdateStage.MIGRATED_TEST_STARTED else UpdateStage.TEST_STARTED
        launcher.launch(session.prepared, prism, settings.load()?.removeDefaultServerList == true)
        return saveUpdate(session.prepared, stage)
    }

    /** The caller confirms that Minecraft reached its main menu and was then closed. */
    fun confirmTestClosed(session: UpdateSession): UpdateSession {
        val stage = when (session.stage) {
            UpdateStage.TEST_STARTED -> UpdateStage.TEST_CONFIRMED
            UpdateStage.MIGRATED_TEST_STARTED -> UpdateStage.MIGRATED_TEST_CONFIRMED
            else -> error("Launch the test before confirming it.")
        }
        return saveUpdate(session.prepared, stage)
    }
}
