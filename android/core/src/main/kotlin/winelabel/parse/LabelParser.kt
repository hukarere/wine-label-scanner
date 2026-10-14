package winelabel.parse

import winelabel.layout.Layout
import winelabel.model.OcrLine
import java.time.LocalDate

/**
 * Wine label field parser: OCR lines in reading order -> [WineLabel]. Originally a port of
 * the (retired) Python prototype; test cases are the JSON files in fixtures/text.
 */

const val FUZZY_MIN_LEN = 6        // names shorter than this must match exactly
const val FUZZY_MIN_RATIO = 0.88   // SequenceMatcher ratio for OCR-garbled names
const val FUZZY_LONG_LEN = 12      // long names carry more evidence, so tolerate more damage
const val FUZZY_LONG_RATIO = 0.8   // e.g. "Cotes de Prows" -> "Côtes de Provence"
const val GLUED_MIN_LEN = 8        // multi-word names this long are also found with spaces lost

/** [value] is String, Int, Double or List<String>. [source] is the OCR line(s) it came from. */
data class Field(val value: Any, val confidence: Double, val source: String)

data class WineLabel(
    val producer: Field? = null,
    val vintage: Field? = null,
    val appellation: Field? = null,
    val classification: Field? = null, // List<String>, e.g. ["AOC", "Grand Cru Classé"]
    val grapes: Field? = null,         // List<String>
    val abv: Field? = null,            // Double, % alcohol by volume
    val volumeMl: Field? = null,       // Int
    val country: Field? = null,
) {
    /** Field name (Python spelling, as in fixtures) -> field. */
    fun fields(): Map<String, Field?> = linkedMapOf(
        "producer" to producer, "vintage" to vintage, "appellation" to appellation,
        "classification" to classification, "grapes" to grapes, "abv" to abv,
        "volume_ml" to volumeMl, "country" to country,
    )

    fun values(): Map<String, Any?> = fields().mapValues { it.value?.value }
}

data class NameHit(val name: RefName, val line: Int, val word: Int, val score: Double)

object LabelParser {

    // ------------------------------------------------------------------ name matching

    fun fuzzyScore(text: String, key: String): Double {
        var score = SequenceMatcher.ratio(text, key)
        // Long names are often cut off at the label's curved edge ("Côtes de Proven..."):
        // also compare against the name's prefix, with a small penalty.
        if (key.length >= FUZZY_LONG_LEN && text.length >= 0.7 * key.length) {
            val prefix = key.take(text.length)
            score = maxOf(score, 0.95 * SequenceMatcher.ratio(text, prefix))
        }
        return score
    }

    /**
     * Non-overlapping occurrences of reference names. Three passes over words still
     * unclaimed: exact word sequences, multi-word names glued into one token, fuzzy.
     */
    fun matchNames(lines: List<String>, names: List<RefName>, fuzzy: Boolean = true): List<NameHit> {
        val words = lines.map { Text.words(it) }
        val used = words.map { BooleanArray(it.size) }
        val hits = mutableListOf<NameHit>()

        fun scan(exact: Boolean) {
            for (name in names) {
                val keyWords = name.key.split(' ')
                val k = keyWords.size
                if (!exact && name.key.length < FUZZY_MIN_LEN) continue
                val minRatio = if (name.key.length >= FUZZY_LONG_LEN) FUZZY_LONG_RATIO else FUZZY_MIN_RATIO
                for ((li, lw) in words.withIndex()) {
                    for (i in 0..lw.size - k) {
                        if ((i until i + k).any { used[li][it] }) continue
                        val window = lw.subList(i, i + k)
                        val score = if (exact) {
                            if (window != keyWords) continue
                            1.0
                        } else {
                            val s = fuzzyScore(window.joinToString(" "), name.key)
                            if (s < minRatio) continue
                            s
                        }
                        for (w in i until i + k) used[li][w] = true
                        hits.add(NameHit(name, li, i, score))
                    }
                }
            }
        }

        fun scanGlued() {
            for (name in names) {
                val glued = name.key.replace(" ", "")
                if (' ' !in name.key || glued.length < GLUED_MIN_LEN) continue
                for ((li, lw) in words.withIndex()) {
                    for ((i, token) in lw.withIndex()) {
                        if (!used[li][i] && token.length > glued.length && glued in token) {
                            used[li][i] = true
                            hits.add(NameHit(name, li, i, 0.9))
                        }
                    }
                }
            }
        }

        scan(exact = true)
        scanGlued()
        if (fuzzy) scan(exact = false)
        return hits.sortedWith(compareBy({ it.line }, { it.word }))
    }

    // ------------------------------------------------------------------ ABV

    private const val NUM = """\d{1,2}(?:[.,]\d{1,2})?"""
    private val abvPatterns = listOf(
        // 13,5% vol | 13.5 % alc./vol | 14% ABV | 12.5% by vol
        Regex("""(?i)($NUM)\s*%\s*(?:alc|vol|abv|by\s+vol)""") to 0.95,
        // Alc. 13.5% | Alcohol 14.5% by volume | Alc. 13,5 % Vol.
        Regex("""(?i)(?:alc|alcohol|alcool|alkohol|alcol)\.?\s*(?:[a-z]+\.?\s*){0,2}?($NUM)\s*%""") to 0.95,
        // bare 13.5% — only trusted when in a plausible wine range
        Regex("""(?<![\d.,])(\d{1,2}[.,]\d)\s*%""") to 0.6,
    )

    fun extractAbv(lines: List<OcrLine>): Field? {
        for ((pattern, conf) in abvPatterns) {
            for (line in lines) {
                for (m in pattern.findAll(line.text)) {
                    val value = m.groupValues[1].replace(",", ".").toDouble()
                    if (value in 4.5..23.0) return Field(value, conf * line.confidence, line.text)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ volume

    private val volumeRe =
        Regex("""(?i)(?<![\d.,])(\d{1,4}(?:[.,]\d{1,3})?)\s*(ml|cl|ltr|lt|litres?|liters?|l)s?(?![a-z])""")
    private val toMl = mapOf("ml" to 1, "cl" to 10)
    private val commonMl = setOf(187, 200, 250, 375, 500, 620, 700, 750, 1000, 1500, 3000, 5000, 6000)

    fun extractVolume(lines: List<OcrLine>): Field? {
        var best: Field? = null
        for (line in lines) {
            for (m in volumeRe.findAll(line.text)) {
                val unit = m.groupValues[2].lowercase()
                // Math.rint: Python's round() rounds half to even.
                val ml = Math.rint(m.groupValues[1].replace(",", ".").toDouble() * (toMl[unit] ?: 1000)).toInt()
                if (ml !in 100..6000) continue
                val conf = (if (ml in commonMl) 0.95 else 0.6) * line.confidence
                if (best == null || conf > best.confidence) best = Field(ml, conf, line.text)
            }
        }
        return best
    }

    // ------------------------------------------------------------------ vintage

    private val yearRe = Regex("""(?i)(?<![\d.,])(19[5-9]\d|20[0-4]\d)(?![\d%])(?!\s*(?:ml|cl)\b)""")
    // Years on these lines are founding/classification dates, not the vintage.
    private val notVintage = Regex(
        """\b(since|est|estd|established|founded|fondee?|depuis|dal|seit|desde|sinds|anno|""" +
            """classification|classement|classe en|family owned)\b"""
    )
    private val vintageHint = Regex("""\b(vintage|millesime|annata|cosecha|jahrgang|vendange|harvest)\b""")

    fun extractVintage(lines: List<OcrLine>, thisYear: Int = LocalDate.now().year): Field? {
        val maxH = lines.maxOfOrNull { it.height }?.takeIf { it != 0.0 } ?: 1.0
        var best: Pair<Double, Field>? = null
        for (line in lines) {
            val norm = Text.normalize(line.text)
            if (notVintage.containsMatchIn(norm)) continue
            for (m in yearRe.findAll(line.text)) {
                val year = m.groupValues[1].toInt()
                if (year > thisYear) continue
                val standalone = line.text.trim() == m.groupValues[1]
                val hinted = vintageHint.containsMatchIn(norm)
                val score = (if (standalone) 2 else 0) + (if (hinted) 2 else 0) + line.height / maxH
                val conf = (if (standalone || hinted) 0.95 else 0.7) * line.confidence
                if (best == null || score > best.first) best = score to Field(year, conf, line.text)
            }
        }
        return best?.second
    }

    // ------------------------------------------------------------------ classification

    // (normalized regex, canonical label). Checked in order; more specific first.
    private val classifications = listOf(
        """\bdocg\b|denominazione di origine controllata e garantita""" to "DOCG",
        """\bdoc\b|denominazione di origine controllata""" to "DOC",
        """\bdoca\b|\bdoq\b|denominacion de origen calificada""" to "DOCa",
        """\bdop\b|denominazione di origine protetta|denominacao de origem protegida""" to "DOP",
        """\bigt\b|indicazione geografica tipica""" to "IGT",
        """\bigp\b|indication geographique protegee|indicazione geografica protetta""" to "IGP",
        """\baop\b|appellation d origine protegee|appellation \w+(?: \w+){0,3} protegee""" to "AOP",
        """\baoc\b|appellation d origine controlee|appellation \w+(?: \w+){0,3} controlee""" to "AOC",
        """\bava\b|american viticultural area""" to "AVA",
        """\bvqa\b|vintners quality alliance""" to "VQA",
        """\bpremier grand cru classe\b""" to "Premier Grand Cru Classé",
        """\bgrand cru classe\b""" to "Grand Cru Classé",
        """\bcru bourgeois\b""" to "Cru Bourgeois",
        """\b(?:premier|1er) cru\b""" to "Premier Cru",
        """\bgrand cru\b""" to "Grand Cru",
        """\bpradikatswein\b""" to "Prädikatswein",
        """\bqualitatswein\b""" to "Qualitätswein",
        """\bgrosses gewachs\b|\bgg\b""" to "Grosses Gewächs",
        """\btrockenbeerenauslese\b""" to "Trockenbeerenauslese",
        """\bbeerenauslese\b""" to "Beerenauslese",
        """\bauslese\b""" to "Auslese",
        """\bspatlese\b""" to "Spätlese",
        """\bkabinett\b""" to "Kabinett",
        """\bgran reserva\b""" to "Gran Reserva",
        """\breserva\b""" to "Reserva",
        """\bcrianza\b""" to "Crianza",
        """\briserva\b""" to "Riserva",
        """\bsuperiore\b""" to "Superiore",
    ).map { (p, label) -> Regex(p) to label }

    // Spanish/Portuguese "DO" is too common a word to match case-insensitively.
    private val doRe = Regex("""\bD\.?O\.?(?![A-Za-z])|Denominaci[oó]n de Origen(?! Calificada)""", RegexOption.IGNORE_CASE)
    private val doStrict = Regex("""\bD\.?O\.?(?![A-Za-z])""")

    fun extractClassification(lines: List<OcrLine>): Field? {
        val found = mutableListOf<String>()
        val sources = mutableListOf<String>()
        // Long legal lines are often split by OCR ("APPELLATION D'ORIGINE" / "PROTÉGÉE"),
        // so after single lines also try each pair of consecutive lines.
        val singles = lines.map { it to null as Int? }
        val pairs = lines.zipWithNext { a, b ->
            OcrLine("${a.text} ${b.text}", minOf(a.confidence, b.confidence), a.box) to
                Text.normalize(a.text).length as Int?
        }
        for ((line, splitAt) in singles + pairs) {
            var claimed = Text.normalize(line.text)
            for ((pattern, label) in classifications) {
                val m = pattern.find(claimed) ?: continue
                val start = m.range.first
                val end = m.range.last + 1
                // A pair only adds matches that cross the line break.
                if (splitAt != null && !(start < splitAt && splitAt < end)) continue
                if (label !in found) {
                    found.add(label)
                    sources.add(line.text)
                    // Blank out the match so "grand cru classe" doesn't also yield "Grand Cru".
                    claimed = claimed.substring(0, start) + " ".repeat(end - start) + claimed.substring(end)
                }
            }
            if (splitAt == null && "DOCa" !in found && "DO" !in found) {
                val m = doRe.find(line.text)
                if (m != null && (doStrict.containsMatchIn(line.text) || "denominaci" in m.value.lowercase())) {
                    found.add("DO")
                    sources.add(line.text)
                }
            }
        }
        if (found.isEmpty()) return null
        return Field(found.toList(), 0.9, sources.distinct().joinToString(" / "))
    }

    // ------------------------------------------------------------------ appellation

    // Android's regex engine (ICU) only knows the inline flags i, m, s, x, w — pass options instead
    // of (?u)/(?U). Kotlin adds Unicode case folding to IGNORE_CASE on the JVM; ICU always has it.
    private val appellationPhrase =
        Regex("""appellation\s+(.+?)\s+(?:contr[oô]l[ée]e|prot[ée]g[ée]e)""", RegexOption.IGNORE_CASE)
    private val marker = Regex(
        """\b(appellation|aoc|aop|docg|doc|doca|do|dop|igt|igp|ava|denominazione|denominacion|""" +
            """controlee|protegee|controllata|garantita|calificada|qualitatswein|vqa)\b"""
    )

    fun extractAppellation(lines: List<OcrLine>): Field? {
        val texts = lines.map { it.text }
        // 1. Explicit French form: "Appellation Pauillac Contrôlée".
        for (line in lines) {
            val m = appellationPhrase.find(line.text) ?: continue
            val norm = Text.normalize(m.groupValues[1])
            if (norm.startsWith("d origine") || norm.startsWith("origine")) continue
            val captured = m.groupValues[1].trim()
            val hits = matchNames(listOf(captured), Reference.regions)
            if (hits.isNotEmpty()) return Field(hits[0].name.display, 0.95 * line.confidence, line.text)
            return Field(captured, 0.8 * line.confidence, line.text)
        }

        // 2. Known region names; prefer those next to an appellation marker,
        //    then the most specific (more words), then the largest text.
        val hits = matchNames(texts, Reference.regions)
        if (hits.isEmpty()) return null

        fun nearMarker(i: Int) =
            (i - 1..i + 1).any { j -> j in texts.indices && marker.containsMatchIn(Text.normalize(texts[j])) }

        val best = hits.maxWith(
            compareBy<NameHit>({ nearMarker(it.line) }, { it.name.key.split(' ').size }, { it.score },
                { lines[it.line].height })
        )
        val conf = (if (nearMarker(best.line)) 0.9 else 0.7) * best.score * lines[best.line].confidence
        return Field(best.name.display, conf, texts[best.line])
    }

    // ------------------------------------------------------------------ grapes

    fun extractGrapes(lines: List<OcrLine>): Field? {
        val hits = matchNames(lines.map { it.text }, Reference.grapes)
        if (hits.isEmpty()) return null
        val names = hits.map { it.name.display }.distinct()
        val sources = hits.map { lines[it.line].text }.distinct()
        val conf = hits.minOf { it.score * lines[it.line].confidence }
        return Field(names, conf, sources.joinToString(" / "))
    }

    // ------------------------------------------------------------------ country

    private val countryPhrase = Regex(
        """\b(?:product of|produce of|produced in|wine of|made in|produit de|produit en|""" +
            """prodotto in|producto de|hecho en|produto de|wein aus|erzeugnis aus|vin de|vino de|vinho de)""" +
            """\s+(?:la |l |the )?(.+)"""
    )

    // Classifications that only exist in one country. (AOP/DOP/IGP are EU-wide.)
    private val classificationCountry = mapOf(
        "AOC" to "France", "DOCG" to "Italy", "DOC" to "Italy", "IGT" to "Italy", "DOCa" to "Spain",
        "AVA" to "United States", "VQA" to "Canada", "Prädikatswein" to "Germany", "Qualitätswein" to "Germany",
        "Premier Grand Cru Classé" to "France", "Grand Cru Classé" to "France", "Cru Bourgeois" to "France",
    )

    fun extractCountry(lines: List<OcrLine>, appellation: Field? = null, classification: Field? = null): Field? {
        // 1. "Product of France" style phrases.
        for (line in lines) {
            val m = countryPhrase.find(Text.normalize(line.text)) ?: continue
            val hits = matchNames(listOf(m.groupValues[1]), Reference.countries, fuzzy = false)
            if (hits.isNotEmpty() && hits[0].word == 0) {
                return Field(hits[0].name.display, 0.95 * line.confidence, line.text)
            }
        }
        // 2. A country name anywhere on the label.
        val hits = matchNames(lines.map { it.text }, Reference.countries, fuzzy = false)
        if (hits.isNotEmpty()) {
            val line = lines[hits[0].line]
            return Field(hits[0].name.display, 0.8 * line.confidence, line.text)
        }
        // 3. Implied by a known appellation.
        if (appellation != null) {
            Reference.regions.firstOrNull { it.display == appellation.value }?.let {
                return Field(it.extra, 0.6 * appellation.confidence, appellation.source)
            }
        }
        // 4. Implied by a national classification system.
        if (classification != null) {
            @Suppress("UNCHECKED_CAST")
            for (label in classification.value as List<String>) {
                classificationCountry[label]?.let { return Field(it, 0.5, classification.source) }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ producer

    private val producerWords = Regex(
        """\b(chateau|domaine|clos|maison|bodegas?|weingut|schloss|tenuta|cantina|cantine|azienda|""" +
            """fattoria|castello|quinta|herdade|vina|winery|wines|estate|vineyards?|cellars?)\b"""
    )
    // Generic words that end names but also fill slogans and legal lines ("POWERLINE VINEYARD",
    // "GOOD WINES TO BE SHARED", "Williams Selyem Winery" under the big "WILLIAMS SELYEM").
    private val generic = setOf("winery", "wines", "estate", "vineyard", "vineyards", "cellar", "cellars")
    // Lines that mention a producer word but are bottling/legal statements.
    private val bottling = Regex(
        """\b(mis en bouteille|bottled|imbottigliato|embotellado|engarrafado|abgefullt|""" +
            """gutsabfullung|erzeugerabfullung|produced by|produit par|imported by|importe par|""" +
            """distributed|contains sulfites|contient des sulfites|enthalt sulfite)\b"""
    )
    private val nonLetters = Regex("[^a-z]")

    /** [claimed] holds indices of lines already used by other fields. */
    fun extractProducer(lines: List<OcrLine>, claimed: Set<Int>): Field? {
        val maxH = lines.maxOfOrNull { it.height }?.takeIf { it != 0.0 } ?: 1.0
        data class Candidate(val score: Double, val index: Int, val conf: Double)
        val candidates = mutableListOf<Candidate>()
        val norms = lines.map { Text.normalize(it.text) }
        for ((i, line) in lines.withIndex()) {
            val norm = norms[i]
            if (bottling.containsMatchIn(norm) || notVintage.containsMatchIn(norm) ||
                nonLetters.replace(norm, "").length < 3
            ) continue
            val keywords = producerWords.findAll(norm).map { it.value }.toList()
            val keyword = keywords.isNotEmpty()
            // Names are short: sentences and slogans ("this little pig stayed home") are not,
            // however large. 5 words only with a keyword ("Domaine de la Romanée Conti").
            val words = norm.split(' ').count { it.isNotEmpty() }
            if (words > 5 || (words == 5 && !keyword)) continue
            // The producer's name is often printed twice (capsule or neck label + label).
            val repeated = if (norms.indices.any { j -> j != i && sameName(norm, norms[j]) }) REPEAT_BONUS else 0.0
            // A producer keyword is a bonus, not an override: big unmarked names
            // ("CLOUDY BAY") should still beat small print like "Maison fondée en 1772".
            if (keyword) {
                val bonus = if (keywords.all { it in generic }) 0.2 else 0.5
                candidates.add(Candidate(bonus + repeated + line.height / maxH, i, 0.9))
            } else if (i !in claimed) {
                candidates.add(Candidate(repeated + line.height / maxH, i, 0.6))
            }
        }
        // Python's max() over (score, index, conf) tuples.
        val top = candidates.maxWithOrNull(compareBy({ it.score }, { it.index }, { it.conf })) ?: return null
        // Big names are often split into pieces on one row ("Christina" | "Dhom").
        val best = lines[top.index]
        val row = Layout.groupRows(lines).first { best in it }
        fun joinable(i: Int) = i !in claimed && !mentionsPlaceOrGrape(lines[i].text) &&
            !bottling.containsMatchIn(norms[i]) && nonLetters.replace(norms[i], "").length >= 2 &&
            norms[i].split(' ').count { it.isNotEmpty() } <= 3
        val rowParts = row.filter { it === best || (it.height >= 0.7 * best.height && joinable(lines.indexOf(it))) }
        // ...or over two lines ("SCHLOSS" / "GOBELSBURG", "DOMAINE" / "LA SUFFRENE"), or side by side
        // but not on one row (slanted script: "Philippe" "Chavy").
        val keywordOnly = Text.normalize(best.text).split(' ').all { producerWords.matches(it) }
        val near = lines.indices.filter { j ->
            val other = lines[j]
            other !in rowParts && joinable(j) && Text.normalize(other.text).split(' ').size + norms[top.index].split(' ').size <= 5 &&
                // Same letter size: the rest of the name. A cuvée under the producer ("Muga" /
                // "Selección Especial", "KAIKEN" / "ultra") differs more (ratios 0.51-0.74 vs >= 0.89).
                (minOf(other.height, best.height) / maxOf(other.height, best.height) >= 0.8 || keywordOnly ||
                    norms[j] in connectors) &&
                (stacked(best, other) || beside(best, other))
        }
        val parts = (rowParts + near.take(2).map { lines[it] }).distinct().sortedWith(readingOrder)
        val text = parts.joinToString(" ") { cleanName(it.text) }
        return Field(text, top.conf * parts.minOf { it.confidence }, parts.joinToString(" | ") { it.text })
    }

    private fun xOverlap(a: OcrLine, b: OcrLine) =
        minOf(a.box.maxOf { it.x }, b.box.maxOf { it.x }) - maxOf(a.left, b.left)
    private fun yOverlap(a: OcrLine, b: OcrLine) =
        minOf(a.box.maxOf { it.y }, b.box.maxOf { it.y }) - maxOf(a.box.minOf { it.y }, b.box.minOf { it.y })

    /** Directly above or below, horizontally overlapping by half the narrower line, gap < half a line. */
    private fun stacked(a: OcrLine, b: OcrLine): Boolean {
        val narrower = minOf(a.box.maxOf { it.x } - a.left, b.box.maxOf { it.x } - b.left)
        return xOverlap(a, b) >= 0.5 * narrower && yOverlap(a, b) >= -0.5 * maxOf(a.height, b.height)
    }

    /** Next to each other, overlapping vertically by half the smaller line, gap < one line height. */
    private fun beside(a: OcrLine, b: OcrLine) =
        yOverlap(a, b) >= 0.5 * minOf(a.height, b.height) && xOverlap(a, b) >= -minOf(a.height, b.height) &&
            xOverlap(a, b) < 0.5 * minOf(a.box.maxOf { it.x } - a.left, b.box.maxOf { it.x } - b.left)

    /** Top to bottom; pieces overlapping vertically by half a line count as one line, left to right. */
    private val readingOrder = Comparator<OcrLine> { a, b ->
        if (yOverlap(a, b) >= 0.5 * minOf(a.height, b.height)) a.left.compareTo(b.left)
        else a.box.minOf { it.y }.compareTo(b.box.minOf { it.y })
    }

    private const val REPEAT_BONUS = 0.5

    /** Small words inside names, often printed smaller ("Casillero" / "del" / "Diablo"). */
    private val connectors = setOf("de", "del", "della", "di", "da", "du", "des", "la", "le", "y", "e", "et", "and", "von", "van", "zu")

    /** Any region, country or grape name in [text], OCR-damaged ones included ("new zealan"). */
    private fun mentionsPlaceOrGrape(text: String) =
        listOf(Reference.regions, Reference.countries, Reference.grapes).any { matchNames(listOf(text), it).isNotEmpty() }

    /** Same name printed twice, allowing for an OCR slip or a cut-off end ("WIN ISLANDS"). */
    private fun sameName(a: String, b: String): Boolean {
        val x = a.replace(" ", ""); val y = b.replace(" ", "")
        if (x.length < 4 || y.length < 4) return false
        return x == y || SequenceMatcher.ratio(x, y) >= 0.85
    }

    // \p{L}\p{N}_ = Unicode \w on both the JVM and Android (ICU), without the (?U) flag ICU rejects.
    private val nameEdges = Regex("""^[^\p{L}\p{N}_"'(]+|[^\p{L}\p{N}_"')]+$""")

    /** Drop trademark symbols and OCR noise around a name ("Schramsberg®" read as "Schramsberg@"). */
    fun cleanName(text: String): String = nameEdges.replace(text.trim(), "")

    // ------------------------------------------------------------------

    /** Indices of lines whose whole content is explained by another field. */
    private fun claimedLines(lines: List<OcrLine>, label: WineLabel): Set<Int> {
        val claimed = mutableSetOf<Int>()
        for ((i, line) in lines.withIndex()) {
            val norm = Text.normalize(line.text)
            var rest = norm
            for (f in listOf(label.vintage, label.abv, label.volumeMl, label.appellation)) {
                if (f != null && f.source == line.text) claimed.add(i)
            }
            for (names in listOf(Reference.regions, Reference.grapes, Reference.countries)) {
                for (h in matchNames(listOf(line.text), names, fuzzy = false)) {
                    rest = rest.replace(h.name.key, " ")
                }
            }
            // Classification terms too: "Beerenauslese" or "RESERVA" alone is not a producer.
            for ((pattern, _) in classifications) rest = pattern.replace(rest, " ")
            if (marker.containsMatchIn(norm) || nonLetters.replace(rest, "").isEmpty()) claimed.add(i)
        }
        return claimed
    }

    /** Extract fields from lines given in reading order. */
    fun parse(lines: List<OcrLine>, thisYear: Int = LocalDate.now().year): WineLabel {
        if (lines.isEmpty()) return WineLabel()
        val vintage = extractVintage(lines, thisYear)
        val abv = extractAbv(lines)
        val volume = extractVolume(lines)
        val classification = extractClassification(lines)
        val appellation = extractAppellation(lines)
        val grapes = extractGrapes(lines)
        val country = extractCountry(lines, appellation, classification)
        val partial = WineLabel(
            vintage = vintage, abv = abv, volumeMl = volume, classification = classification,
            appellation = appellation, grapes = grapes, country = country,
        )
        return partial.copy(producer = extractProducer(lines, claimedLines(lines, partial)))
    }
}
