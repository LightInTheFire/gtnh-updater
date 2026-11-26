package ru.light.gtnh.updater.presentation.tui

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.light.gtnh.updater.core.Updater
import ru.light.gtnh.updater.core.model.ClientArchive
import ru.light.gtnh.updater.core.model.DownloadedArchive
import ru.light.gtnh.updater.core.model.TransferProgress

internal class ArchiveDownload(archive: ClientArchive, updater: Updater) {
    private val latestProgress = atomic(TransferProgress())
    private val operation = BackgroundOperation(Dispatchers.IO) {
        updater.downloadArchive(archive) { latestProgress.value = it }
    }

    fun progress(): TransferProgress = latestProgress.value

    fun outcome(): Result<DownloadedArchive>? = operation.outcome()

    fun cancel() = operation.cancel()
}
