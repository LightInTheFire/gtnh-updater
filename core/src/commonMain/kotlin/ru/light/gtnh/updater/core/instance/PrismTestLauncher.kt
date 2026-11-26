package ru.light.gtnh.updater.core.instance

import okio.FileSystem
import okio.Path
import ru.light.gtnh.updater.core.config.removeDefaultServersMod
import ru.light.gtnh.updater.core.model.PreparedInstance
import ru.light.gtnh.updater.core.model.PrismProfile
import ru.light.gtnh.updater.core.platform.startPrismProcess

internal const val TEST_INSTANCE_PREFIX = "gtnh-updater-test-"
private const val PRISM_ROOT_OPTION = "--dir"
private const val PRISM_LAUNCH_OPTION = "--launch"

internal class PrismTestLauncher(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val start: (PrismProfile, List<String>) -> Unit = ::startPrismProcess,
) {
    private val names = PrismInstanceName(fs)

    fun register(
        prepared: PreparedInstance,
        profile: PrismProfile,
        onRegistered: (PreparedInstance) -> Unit = {},
    ): PreparedInstance = registerIn(prepared, profile.instances, onRegistered)

    fun registerIn(
        prepared: PreparedInstance,
        instances: Path,
        onRegistered: (PreparedInstance) -> Unit,
    ): PreparedInstance {
        val staging = requireNotNull(prepared.candidate.parent)
        val destination = registrationDestination(prepared, instances)
        check(!fs.exists(destination)) { "The Prism test instance already exists." }
        check(!fs.exists(prepared.candidate / PREPARATION_MANIFEST)) {
            "The prepared instance already contains an update plan."
        }
        val test = prepared.copy(candidate = destination)
        fs.atomicMove(prepared.candidate, destination)
        try {
            writePreparedPlan(fs, destination, test)
            labelCandidate(test)
            onRegistered(test)
        } catch (error: Exception) {
            runCatching { names.restore(destination) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { fs.delete(destination / PREPARATION_MANIFEST) }
                .exceptionOrNull()?.let(error::addSuppressed)
            runCatching { fs.atomicMove(destination, prepared.candidate) }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        runCatching {
            val manifest = staging / PREPARATION_MANIFEST
            if (fs.list(staging) == listOf(manifest)) {
                fs.delete(manifest)
                fs.delete(staging)
            }
        }
        return test
    }

    fun registrationDestination(prepared: PreparedInstance, instances: Path): Path {
        val staging = requireNotNull(prepared.candidate.parent)
        check(prepared.source.parent == instances && prepared.candidate.name == "instance" &&
            staging.name.startsWith(STAGING_PREFIX) && staging.parent == instances &&
            fs.metadataOrNull(staging)?.symlinkTarget == null
        ) { "The prepared instance is outside this instances directory." }
        val id = staging.name.removePrefix(STAGING_PREFIX)
        return instances / "$TEST_INSTANCE_PREFIX$id"
    }

    fun restoreRegistration(prepared: PreparedInstance, destination: Path) {
        check(destination == registrationDestination(prepared, checkNotNull(prepared.source.parent)) &&
            !fs.exists(prepared.candidate) && fs.metadataOrNull(destination)?.symlinkTarget == null &&
            instanceGameDirectory(fs, destination) != null
        ) { "Could not recover the candidate registration. The instance folders were kept." }
        names.restore(destination)
        fs.delete(destination / PREPARATION_MANIFEST)
        fs.atomicMove(destination, prepared.candidate)
    }

    fun labelCandidate(test: PreparedInstance) {
        check(test.candidate.name.startsWith(TEST_INSTANCE_PREFIX)) { "Select a registered Prism test instance." }
        names.label(test.candidate, "Test", test.targetVersion)
    }

    fun launch(test: PreparedInstance, profile: PrismProfile, removeDefaultServerList: Boolean = false) {
        check(test.candidate.parent == profile.instances && test.candidate.name.startsWith(TEST_INSTANCE_PREFIX)) {
            "Select a registered Prism test instance."
        }
        labelCandidate(test)
        if (removeDefaultServerList) removeDefaultServerList(test)
        start(
            profile,
            listOf(PRISM_ROOT_OPTION, profile.root.toString(), PRISM_LAUNCH_OPTION, test.candidate.name),
        )
    }

    fun removeDefaultServerList(test: PreparedInstance) {
        val game = checkNotNull(instanceGameDirectory(fs, test.candidate)) { "The candidate instance is unavailable." }
        removeDefaultServersMod(fs, game)
    }
}
