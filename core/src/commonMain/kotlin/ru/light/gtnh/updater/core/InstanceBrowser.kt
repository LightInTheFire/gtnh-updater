package ru.light.gtnh.updater.core

import okio.Path
import ru.light.gtnh.updater.core.model.InstanceChoice

interface InstanceBrowser {
    fun discover(): List<InstanceChoice>
    fun startingDirectory(): Path
    fun searchFolders(directory: Path, query: String): List<Path>
    fun instance(path: Path): InstanceChoice?
}
