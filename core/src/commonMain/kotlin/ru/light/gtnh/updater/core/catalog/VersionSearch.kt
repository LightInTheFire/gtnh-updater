package ru.light.gtnh.updater.core.catalog

import ru.light.gtnh.updater.core.model.PackVersion

private const val EXACT_MATCH_SCORE = 0
private const val SUBSTRING_MATCH_PENALTY = 1
private const val SUBSEQUENCE_MATCH_PENALTY = 100

fun searchVersions(versions: List<PackVersion>, query: String): List<PackVersion> =
    fuzzySearch(versions, query) { listOf(it.id, it.channel.label) }

fun <T> fuzzySearch(items: List<T>, query: String, fields: (T) -> List<String>): List<T> {
    val terms = query.lowercase().split(' ').filter(String::isNotBlank)
    if (terms.isEmpty()) return items

    return items.mapNotNull { item ->
        val searchable = fields(item).map(String::lowercase)
        var score = 0
        for (term in terms) {
            score += searchable.firstNotNullOfOrNull { matchScore(it, term) } ?: return@mapNotNull null
        }
        item to score
    }.sortedBy { it.second }.map { it.first }
}

private fun matchScore(text: String, term: String): Int? {
    if (text == term) return EXACT_MATCH_SCORE
    val substring = text.indexOf(term)
    if (substring >= 0) return SUBSTRING_MATCH_PENALTY + substring

    var previous = -1
    var score = SUBSEQUENCE_MATCH_PENALTY
    for (character in term) {
        val position = text.indexOf(character, previous + 1)
        if (position < 0) return null
        score += position - previous - 1
        previous = position
    }
    return score
}
