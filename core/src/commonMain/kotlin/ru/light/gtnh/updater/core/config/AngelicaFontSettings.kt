package ru.light.gtnh.updater.core.config

internal const val ANGELICA_CONFIG = "config/angelica.cfg"

private val fontSection = Regex("""(?m)^[\t ]*customfont[\t ]*\{[\t ]*\r?\n([^{}]*?)^[\t ]*\}[\t ]*\r?$""")
private val property = Regex("""(?m)^([\t ]*[BIS]):([A-Za-z][A-Za-z0-9]*)=([^\r\n]*)""")
private val declaredRange = Regex("""\[range:\s*(-?[0-9.]+)\s*~\s*(-?[0-9.]+)""")
private val fontNames = setOf("customFontNamePrimary", "customFontNameFallback")
private val fontToggles = setOf("enableCustomFont", "enableGlyphReplacements", "enableModernFont")
private val fontIntegers = setOf("boldCopies", "customFontQuality", "fontAAMode", "fontAAStrength", "shadowCopies")
private val fontScales = setOf(
    "customFontScale", "fontBrightness", "fontShadowOffset", "fontShadowOffsetUC",
    "glyphAspect", "glyphScale", "glyphSpacing", "whitespaceScale",
)

internal fun carryAngelicaFontSettings(before: String, after: String): String {
    val source = fontSection.findAll(before).singleOrNull()?.groups?.get(1) ?: return after
    val target = fontSection.findAll(after).singleOrNull()?.groups?.get(1) ?: return after
    val oldByName = property.findAll(source.value).groupBy { it.groupValues[2] }
    val newByName = property.findAll(target.value).groupingBy { it.groupValues[2] }.eachCount()
    val updated = property.replace(target.value) { match ->
        val (prefix, key, newValue) = match.destructured
        val old = oldByName[key]?.singleOrNull()
        val type = prefix.last()
        if (old == null || newByName[key] != 1 || old.groupValues[1].last() != type) {
            return@replace match.value
        }
        val value = old.groupValues[3]
        val compatible = when (key) {
            in fontNames -> type == 'S' && value.isNotBlank()
            in fontToggles -> type == 'B' && (value == "true" || value == "false")
            in fontIntegers -> type == 'I' && value.toIntOrNull() != null
            in fontScales -> type == 'S' && value.toDoubleOrNull()?.isFinite() == true
            else -> false
        }
        val inRange = if (key in fontIntegers || key in fontScales) {
            val comment = target.value.substring(0, match.range.first).trimEnd().substringAfterLast('\n')
            val bounds = declaredRange.find(comment)
            if (bounds == null) true else {
                val (minimum, maximum) = bounds.destructured
                val limitStart = minimum.toDoubleOrNull()
                val limitEnd = maximum.toDoubleOrNull()
                val number = value.toDoubleOrNull()
                number != null && limitStart != null && limitEnd != null && number in limitStart..limitEnd
            }
        } else true
        if (compatible && inRange && value != newValue) "$prefix:$key=$value" else match.value
    }
    return after.replaceRange(target.range, updated)
}
