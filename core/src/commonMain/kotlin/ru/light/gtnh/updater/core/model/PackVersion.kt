package ru.light.gtnh.updater.core.model

enum class PackChannel(val label: String) {
    STABLE("stable"),
    BETA("beta"),
    DAILY("daily"),
}

class PackVersion private constructor(val id: String, val channel: PackChannel) {
    val sourceUrl: String
        get() = when (channel) {
            PackChannel.STABLE, PackChannel.BETA -> "https://www.gtnewhorizons.com/version-history/"
            PackChannel.DAILY -> "https://github.com/GTNewHorizons/GTNH-Daily-Builds/releases/tag/$id"
        }

    companion object {
        private val stable = Regex("""[0-9]+\.[0-9]+\.[0-9]+(?:-QF)?""", RegexOption.IGNORE_CASE)
        private val beta = Regex("""[0-9]+\.[0-9]+\.[0-9]+-(?:beta-|rc-?)[1-9][0-9]*""", RegexOption.IGNORE_CASE)
        private val daily = Regex("""daily-[0-9]{4}-[0-9]{2}-[0-9]{2}\+[1-9][0-9]*""")

        internal fun fromReleaseIndex(id: String, channel: PackChannel): PackVersion? {
            val inferred = parse(id) ?: return null
            if (channel == PackChannel.DAILY || inferred.channel == PackChannel.DAILY) return null
            return PackVersion(id, channel)
        }

        fun parse(id: String): PackVersion? = when {
            stable.matches(id) -> PackVersion(id, PackChannel.STABLE)
            beta.matches(id) -> PackVersion(id, PackChannel.BETA)
            daily.matches(id) -> PackVersion(id, PackChannel.DAILY)
            else -> null
        }
    }
}
