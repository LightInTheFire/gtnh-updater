package ru.light.gtnh.updater.core.model

import okio.Path

data class DownloadedArchive(val path: Path, val fromCache: Boolean)
