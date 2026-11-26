package ru.light.gtnh.updater.core.instance

import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage

internal class CandidateRegistration(
    home: Path?,
    private val updates: UpdateSessionStore,
    private val launcher: PrismTestLauncher,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private val journal = UpdateSessionStore(fs, home, "pending-registration.json")

    fun register(
        prepared: PreparedInstance,
        instances: Path,
        stage: UpdateStage,
        beforeSave: (PreparedInstance) -> Unit = {},
    ): UpdateSession {
        recover()
        val destination = launcher.registrationDestination(prepared, instances)
        check(fs.metadataOrNull(destination) == null) { "The candidate instance already exists." }
        journal.save(UpdateSession(prepared, stage, destination))
        val registered = try {
            launcher.registerIn(prepared, instances) {
                beforeSave(it)
                updates.save(UpdateSession(it, stage))
            }
        } catch (error: Exception) {
            runCatching { recover() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        journal.clear()
        return UpdateSession(registered, stage)
    }

    fun recover() {
        val pending = journal.load(validateInstances = false) ?: return
        val prepared = pending.prepared
        val instances = checkNotNull(prepared.source.parent)
        val destination = launcher.registrationDestination(prepared, instances)
        check(pending.displaced == destination &&
            pending.stage in setOf(UpdateStage.REGISTERED, UpdateStage.NEW_CONFIGS_SELECTED)
        ) { "Invalid candidate registration journal." }
        val saved = updates.load(validateInstances = false)
        when {
            saved?.prepared == prepared.copy(candidate = destination) && saved.stage == pending.stage &&
                !fs.exists(prepared.candidate) && instanceGameDirectory(fs, destination) != null -> Unit
            saved?.prepared == prepared && saved.stage == UpdateStage.PREPARED -> {
                if (fs.metadataOrNull(destination) != null) launcher.restoreRegistration(prepared, destination)
                check(instanceGameDirectory(fs, prepared.candidate) != null) { "The prepared instance is unavailable." }
            }
            else -> error("Could not recover the candidate registration. The instance folders were kept.")
        }
        journal.clear()
    }
}
