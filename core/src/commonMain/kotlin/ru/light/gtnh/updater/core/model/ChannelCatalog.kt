package ru.light.gtnh.updater.core.model

data class ClientArchive(val label: String, val url: String)

data class ChannelCatalog(
    val versions: List<PackVersion>,
    val archives: Map<String, List<ClientArchive>> = emptyMap(),
    val status: String,
)
