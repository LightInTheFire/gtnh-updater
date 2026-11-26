package ru.light.gtnh.updater.core.model

import okio.Path

data class PreparationProgress(val extractedEntries: Int = 0, val totalEntries: Int = 0)

data class InstalledInstance(val directory: Path, val name: String)

data class PreparedInstance(
    val source: Path,
    val candidate: Path,
    val currentVersion: String?,
    val targetVersion: String?,
    internal val shippedConfigs: Set<String>? = null,
)

class TestedInstance internal constructor(internal val prepared: PreparedInstance)

enum class UpdateStage {
    PREPARED, REGISTERED, TEST_STARTED, TEST_CONFIRMED,
    MIGRATED_TEST_STARTED, MIGRATED_TEST_CONFIRMED,
    ACTIVATING, ACTIVATED, ROLLING_BACK, ROLLED_BACK,

    ;

    internal val canResume: Boolean
        get() = when (this) {
            PREPARED, REGISTERED, TEST_STARTED, TEST_CONFIRMED, MIGRATED_TEST_STARTED, MIGRATED_TEST_CONFIRMED -> true
            ACTIVATING, ACTIVATED, ROLLING_BACK, ROLLED_BACK -> false
        }
}

class UpdateSession internal constructor(
    val prepared: PreparedInstance,
    val stage: UpdateStage,
    internal val displaced: Path? = null,
) {
    val tested: TestedInstance?
        get() = if (stage == UpdateStage.TEST_CONFIRMED) TestedInstance(prepared) else null
}
