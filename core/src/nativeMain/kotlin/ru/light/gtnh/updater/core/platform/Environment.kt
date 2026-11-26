package ru.light.gtnh.updater.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun readEnvironmentVariable(name: String): String? = getenv(name)?.toKStringFromUtf8()
