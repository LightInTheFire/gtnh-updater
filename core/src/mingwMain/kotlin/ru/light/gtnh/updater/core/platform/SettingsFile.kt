package ru.light.gtnh.updater.core.platform

import okio.Path

internal actual fun protectSettingsFile(path: Path) {
    // The settings file inherits the ACL of the current user's roaming profile directory.
}
