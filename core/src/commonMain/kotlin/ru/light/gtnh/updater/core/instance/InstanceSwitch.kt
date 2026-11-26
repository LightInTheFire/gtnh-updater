package ru.light.gtnh.updater.core.instance

import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.config.MIGRATION_MARKER
import ru.light.gtnh.updater.core.model.UpdateSession
import ru.light.gtnh.updater.core.model.UpdateStage
import ru.light.gtnh.updater.core.platform.appHomeDirectory

internal const val BACKUP_PREFIX = ".gtnh-updater-backup-"
internal const val DISPLACED_PREFIX = ".gtnh-updater-displaced-"

internal class InstanceSwitch(
    private val fs: FileSystem = FileSystem.SYSTEM,
    home: Path? = appHomeDirectory(),
    private val updates: UpdateSessionStore = UpdateSessionStore(fs, home),
) {
    private val last = UpdateSessionStore(fs, home, "last-switch.json")
    private val journal = UpdateSessionStore(fs, home, "pending-switch.json")
    private val names = PrismInstanceName(fs)

    fun isMigrated(session: UpdateSession): Boolean =
        instanceGameDirectory(fs, session.prepared.candidate)?.let { fs.exists(it / MIGRATION_MARKER) } == true

    fun lastSwitch(validateInstances: Boolean = true): UpdateSession? {
        recover()
        return last.load(validateInstances)?.also { saved ->
            // Label copies created by older versions when their records are still available.
            runCatching { labelSavedCopy(saved) }
        }
    }

    fun activate(session: UpdateSession): UpdateSession {
        recover()
        val saved = updates.load()
        check(saved?.prepared == session.prepared && saved.stage == session.stage && session.stage in setOf(
            UpdateStage.TEST_CONFIRMED, UpdateStage.MIGRATED_TEST_STARTED, UpdateStage.MIGRATED_TEST_CONFIRMED,
        )) {
            "Confirm that the clean test reached the Minecraft main menu and was closed."
        }
        check(isMigrated(session)) { "Complete the data and config migration first." }
        return switch(session, UpdateStage.ACTIVATING, BACKUP_PREFIX, TEST_INSTANCE_PREFIX)
    }

    fun rollback(session: UpdateSession): UpdateSession {
        recover()
        val saved = last.load()
        check(saved?.prepared == session.prepared && saved.stage == UpdateStage.ACTIVATED) {
            "This update is no longer available for rollback."
        }
        return switch(session, UpdateStage.ROLLING_BACK, DISPLACED_PREFIX, BACKUP_PREFIX)
    }

    private fun switch(session: UpdateSession, stage: UpdateStage, prefix: String, incomingPrefix: String): UpdateSession {
        val source = session.prepared.source
        val incoming = session.prepared.candidate
        val parent = checkNotNull(source.parent)
        val displaced = parent / "$prefix${Uuid.random()}"
        validatePaths(source, incoming, displaced, incomingPrefix, prefix)
        check(isInstance(source) && isInstance(incoming) && !fs.exists(displaced)) {
            "Both instances must be available before switching."
        }

        val pending = UpdateSession(session.prepared, stage, displaced)
        journal.save(pending)
        try {
            fs.atomicMove(source, displaced)
            fs.atomicMove(incoming, source)
        } catch (error: Exception) {
            runCatching { recover() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return finish(pending)
    }

    fun recover() {
        val pending = journal.load(validateInstances = false) ?: return
        val source = pending.prepared.source
        val incoming = pending.prepared.candidate
        val displaced = checkNotNull(pending.displaced)
        val activation = when (pending.stage) {
            UpdateStage.ACTIVATING -> true
            UpdateStage.ROLLING_BACK -> false
            else -> error("Unexpected instance switch state.")
        }
        validatePaths(
            source, incoming, displaced,
            if (activation) TEST_INSTANCE_PREFIX else BACKUP_PREFIX,
            if (activation) BACKUP_PREFIX else DISPLACED_PREFIX,
        )
        when {
            isInstance(source) && isInstance(incoming) && !fs.exists(displaced) -> journal.clear()
            !fs.exists(source) && isInstance(incoming) && isInstance(displaced) -> {
                fs.atomicMove(displaced, source)
                journal.clear()
            }
            isInstance(source) && !fs.exists(incoming) && isInstance(displaced) -> finish(pending)
            else -> error("Could not recover the pending instance switch. The instance folders were kept.")
        }
    }

    private fun finish(pending: UpdateSession): UpdateSession {
        val stage = if (pending.stage == UpdateStage.ACTIVATING) UpdateStage.ACTIVATED else UpdateStage.ROLLED_BACK
        val original = pending.prepared
        val result = UpdateSession(original.copy(candidate = checkNotNull(pending.displaced)), stage)
        names.restore(original.source)
        labelSavedCopy(result)
        last.save(result)
        val active = updates.load(validateInstances = false)
        if (active?.prepared?.source == original.source &&
            (active.prepared.candidate == original.candidate || stage == UpdateStage.ROLLED_BACK)
        ) {
            updates.save(result)
        }
        journal.clear()
        return result
    }

    private fun labelSavedCopy(session: UpdateSession) {
        val prepared = session.prepared
        val copy = prepared.candidate
        if (copy.parent != prepared.source.parent || fs.metadataOrNull(copy)?.symlinkTarget != null) return
        when {
            session.stage == UpdateStage.ACTIVATED && copy.name.startsWith(BACKUP_PREFIX) ->
                names.label(copy, "Backup", prepared.currentVersion)
            session.stage == UpdateStage.ROLLED_BACK && copy.name.startsWith(DISPLACED_PREFIX) ->
                names.label(copy, "After rollback", prepared.targetVersion)
        }
    }

    private fun validatePaths(source: Path, incoming: Path, displaced: Path, incomingPrefix: String, displacedPrefix: String) {
        check(source.isAbsolute && source.parent != null && source.parent == incoming.parent && source.parent == displaced.parent &&
            source.normalized() == source && incoming.normalized() == incoming && displaced.normalized() == displaced &&
            source != incoming && source != displaced && incoming != displaced &&
            !source.name.startsWith(BACKUP_PREFIX) && !source.name.startsWith(DISPLACED_PREFIX) &&
            incoming.name.startsWith(incomingPrefix) && displaced.name.startsWith(displacedPrefix)
        ) { "The active instance and its copies must share one instance directory." }
        for (path in listOf(source, incoming, displaced)) {
            check(fs.metadataOrNull(path)?.symlinkTarget == null) { "Linked instance directories are unsupported." }
        }
    }

    private fun isInstance(path: Path): Boolean =
        instanceGameDirectory(fs, path)?.let { fs.metadata(it).symlinkTarget == null } == true
}
