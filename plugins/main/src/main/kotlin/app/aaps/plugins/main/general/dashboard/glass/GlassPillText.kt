package app.aaps.plugins.main.general.dashboard.glass

/** Text of a pill with two values, and where each value is (for its own color). */
data class GlassTwoValues(val text: String, val first: IntRange?, val second: IntRange?)

/** Placeholders put in the template first; they are never in a real value. */
internal const val GLASS_MARK_FIRST = "\u0001"
internal const val GLASS_MARK_SECOND = "\u0002"

/**
 * T28: fills a two-value template and gives the real position of each value. The template is formatted with
 * [GLASS_MARK_FIRST] / [GLASS_MARK_SECOND] (`stringResource(id, GLASS_MARK_FIRST, GLASS_MARK_SECOND)`), so the
 * translation decides the order and the separator (also right-to-left). Searching the values in the text was wrong
 * without spaces: pump `38` and MedLink `38` found the same place.
 * @return the text; a range is null when its value is empty
 */
fun placeTwoValues(marked: String, first: String, second: String): GlassTwoValues {
    val text = StringBuilder()
    var firstRange: IntRange? = null
    var secondRange: IntRange? = null
    var i = 0
    while (i < marked.length) {
        val rest = marked.substring(i)
        when {
            rest.startsWith(GLASS_MARK_FIRST)  -> {
                if (first.isNotEmpty()) firstRange = text.length until text.length + first.length
                text.append(first)
                i += GLASS_MARK_FIRST.length
            }

            rest.startsWith(GLASS_MARK_SECOND) -> {
                if (second.isNotEmpty()) secondRange = text.length until text.length + second.length
                text.append(second)
                i += GLASS_MARK_SECOND.length
            }

            else                               -> {
                text.append(marked[i])
                i++
            }
        }
    }
    return GlassTwoValues(text.toString(), firstRange, secondRange)
}
