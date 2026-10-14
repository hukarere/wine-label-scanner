package winelabel

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import winelabel.parse.LabelParser

/** Runs every JSON case in fixtures/text through the parser. */
class ParserFixtureTest {
    @TestFactory
    fun sharedFixtures(): List<DynamicTest> {
        val files = Fixtures.root.resolve("text").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 8, "expected shared fixtures in ${Fixtures.root}")
        return files.map { file ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) {
                val fixture = Fixtures.readJson(file).jsonObject
                val label = LabelParser.parse(Fixtures.linesFromRows(fixture["lines"]!!.jsonArray), thisYear = 2026)
                val actual = label.values()
                val wrong = fixture["expected"]!!.jsonObject.mapNotNull { (field, exp) ->
                    val expected = Fixtures.toKotlin(exp)
                    if (Fixtures.valuesEqual(expected, actual[field])) null
                    else "$field: expected=$expected got=${actual[field]}"
                }
                assertTrue(wrong.isEmpty(), wrong.joinToString("; "))
            }
        }
    }
}
