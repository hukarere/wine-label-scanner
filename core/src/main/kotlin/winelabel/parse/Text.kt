package winelabel.parse

import java.text.Normalizer

object Text {
    private val dottedInner = Regex("""\b([A-Za-z])\.(?=[A-Za-z]\b\.?)""")
    private val dottedEnd = Regex("""\b([A-Za-z])\.(?=\s|$)""")
    private val combining = Regex("""\p{M}+""")
    private val nonWord = Regex("""[^a-z0-9%]+""")
    private val digitWord = Regex("""(?<=\d)(?=[a-z]{3})|(?<=[a-z]{3})(?=\d)""")

    fun stripAccents(s: String): String =
        combining.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "")

    /**
     * Lowercase, accent-free, punctuation-free words separated by single spaces.
     * Dotted abbreviations collapse first so "D.O.C." -> "doc" and "U.S.A." -> "usa".
     */
    fun normalize(s: String): String {
        var t = dottedInner.replace(s, "$1")
        t = dottedEnd.replace(t, "$1")
        t = stripAccents(t).lowercase()
        t = nonWord.replace(t, " ")
        // OCR often runs numbers into words ("7chardonnay", "vintage2004"); split them
        // apart, but keep short unit/ordinal suffixes like "750ml" and "1er" intact.
        return digitWord.replace(t, " ").trim()
    }

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }
}
