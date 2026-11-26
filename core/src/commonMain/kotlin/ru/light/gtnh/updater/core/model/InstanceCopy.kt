package ru.light.gtnh.updater.core.model

import okio.Path

enum class InstanceCopyKind { STAGING, TEST, BACKUP, DISPLACED }

data class InstanceCopy(val path: Path, val kind: InstanceCopyKind, val protection: String?)
