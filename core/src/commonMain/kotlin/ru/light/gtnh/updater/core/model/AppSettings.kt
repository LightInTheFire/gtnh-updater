package ru.light.gtnh.updater.core.model

const val MAX_GITHUB_TOKEN_LENGTH = 512

data class AppSettings(
    val githubToken: String? = null,
    val removeDefaultServerList: Boolean = false,
    val transferExtraMods: Boolean = false,
) {
    init {
        require(githubToken == null ||
            (githubToken.length in 1..MAX_GITHUB_TOKEN_LENGTH && githubToken.all { it in '!'..'~' })
        ) { "Invalid GitHub token format." }
    }

    override fun toString(): String =
        "AppSettings(githubToken=<redacted>, removeDefaultServerList=$removeDefaultServerList, transferExtraMods=$transferExtraMods)"
}
