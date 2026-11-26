package ru.light.gtnh.updater.core.model

import okio.Path

data class PrismProfile(val root: Path, val instances: Path, val flatpak: Boolean)

data class InstanceChoice(val directory: Path, val name: String, val launcher: String? = null)
