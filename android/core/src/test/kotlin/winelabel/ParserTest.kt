package winelabel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import winelabel.model.OcrLine
import winelabel.model.Point
import winelabel.parse.LabelParser
import winelabel.parse.Reference
import winelabel.parse.SequenceMatcher
import winelabel.parse.Text

/** Mirrors tests/test_parser.py. */
class ParserTest {
    private fun l(text: String, h: Double = 20.0) = OcrLine.rect(text, 0.99, 0.0, 0.0, 100.0, h)

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', nullValues = ["null"],
        value = [
            "13,5% vol|13.5", "13.5 % VOL.|13.5", "Alc. 14.5% by vol|14.5", "ALC 12% BY VOLUME|12.0",
            "alcool 12,5 %|12.5", "14% ABV|14.0", "100% Merlot|null", "Sangiovese 80%|null",
        ],
    )
    fun abv(text: String, expected: Double?) {
        assertEquals(expected, LabelParser.extractAbv(listOf(l(text)))?.value)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', nullValues = ["null"],
        value = [
            "750 ml|750", "750ML|750", "75 cl|750", "75cl|750", "0,75 l|750", "e 0,75 L|750", "1.5 L|1500",
            "375 mL|375", "Since 1855|null", "13,5% vol|null", "750 MLS|750",
        ],
    )
    fun volume(text: String, expected: Int?) {
        assertEquals(expected, LabelParser.extractVolume(listOf(l(text)))?.value)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', nullValues = ["null"],
        value = [
            "2015|2015", "Established 1890;2012|2012", "Since 1772|null", "Grand Cru Classé en 1855;2010|2010",
            "2031|null", "Millésime 2005|2005", "750 ml 2019|2019",
        ],
    )
    fun vintage(texts: String, expected: Int?) {
        assertEquals(expected, LabelParser.extractVintage(texts.split(";").map { l(it) }, thisYear = 2026)?.value)
    }

    @Test
    fun vintagePrefersStandaloneLargeYear() {
        val lines = listOf(l("Cuvée 1998 edition", 10.0), l("2018", 40.0))
        assertEquals(2018, LabelParser.extractVintage(lines, 2026)!!.value)
    }

    @Test
    fun normalize() {
        assertEquals("chateau cote rotie", Text.normalize("Château  Côte-Rôtie!"))
        assertEquals("docg", Text.normalize("D.O.C.G."))
        assertEquals("usa", Text.normalize("U.S.A."))
        assertEquals("7 chardonnay vintage 2004", Text.normalize("7chardonnay VINTAGE2004"))
        assertEquals("750ml 1er cru", Text.normalize("750ml 1er Cru"))
    }

    @Test
    fun sequenceMatcherMatchesPython() {
        // Values from Python difflib.SequenceMatcher(None, a, b).ratio()
        assertEquals(0.7741935483870968, SequenceMatcher.ratio("cotes de prows", "cotes de provence"), 1e-12)
        assertEquals(1.0, SequenceMatcher.ratio("", ""))
        assertEquals(0.0, SequenceMatcher.ratio("abc", "xyz"))
    }

    @Test
    fun longestGrapeWins() {
        val hits = LabelParser.matchNames(listOf("Cabernet Sauvignon & Sauvignon Blanc"), Reference.grapes)
        assertEquals(listOf("Cabernet Sauvignon", "Sauvignon Blanc"), hits.map { it.name.display })
    }

    @Test
    fun fuzzyGrape() {
        val hits = LabelParser.matchNames(listOf("Cabernet Sauvignan"), Reference.grapes)
        assertEquals(listOf("Cabernet Sauvignon"), hits.map { it.name.display })
        assertTrue(hits[0].score < 1)
    }

    @Test
    fun shortNamesNeedExactMatch() {
        assertTrue(LabelParser.matchNames(listOf("Gamy"), Reference.grapes).isEmpty())
    }

    @Test
    fun gluedMultiwordName() {
        val hits = LabelParser.matchNames(listOf("100%NORTHCOASTSPARKLINGWINE"), Reference.regions)
        assertEquals(listOf("North Coast"), hits.map { it.name.display })
    }

    @Test
    fun truncatedLongNameMatchesPrefix() {
        val hits = LabelParser.matchNames(listOf("Cotes de Prows"), Reference.regions)
        assertEquals(listOf("Côtes de Provence"), hits.map { it.name.display })
    }

    @Test
    fun similarNamesDoNotCrossMatch() {
        val names = Reference.grapes + Reference.regions
        assertTrue("Côtes de Provence" !in LabelParser.matchNames(listOf("Cotes de Nuits"), names).map { it.name.display })
        assertTrue("Sauvignon Blanc" !in LabelParser.matchNames(listOf("Sauvignon Gris"), names).map { it.name.display })
    }

    @Test
    fun classificationSplitAcrossLines() {
        val label = LabelParser.parse(listOf(l("APPELLATION D'ORIGINE"), l("E PROTÉGEE")))
        assertEquals(listOf("AOP"), label.classification!!.value)
    }

    @Test
    fun classificationPairDoesNotDuplicate() {
        val label = LabelParser.parse(listOf(l("Grand Cru Classé en 1855"), l("Pauillac")))
        assertEquals(listOf("Grand Cru Classé"), label.classification!!.value)
    }

    @Test
    fun countryFromClassification() {
        val label = LabelParser.parse(listOf(l("Some Winery", 40.0), l("VQA"), l("2017")))
        assertEquals("Canada", label.country!!.value)
    }

    @Test
    fun producerSymbolsAndSplitRow() {
        val lines = listOf(
            OcrLine("Christina", 0.95, listOf(Point(0.0, 0.0), Point(300.0, 0.0), Point(300.0, 100.0), Point(0.0, 100.0))),
            OcrLine("Dhom®", 0.9, listOf(Point(320.0, 5.0), Point(500.0, 5.0), Point(500.0, 95.0), Point(320.0, 95.0))),
            OcrLine("Der Riesling", 0.99, listOf(Point(0.0, 300.0), Point(300.0, 300.0), Point(300.0, 340.0), Point(0.0, 340.0))),
        )
        assertEquals("Christina Dhom", LabelParser.parse(lines).producer!!.value)
    }

    @Test
    fun cleanNameKeepsAccents() {
        assertEquals("Château Lafîte", LabelParser.cleanName("«Château Lafîte»"))
        assertEquals("CHÂTEAU", LabelParser.cleanName("CHÂTEAU"))
    }

    @Test
    fun emptyInput() {
        assertTrue(LabelParser.parse(emptyList()).values().values.all { it == null })
        assertNull(LabelParser.extractAbv(emptyList()))
    }
}
