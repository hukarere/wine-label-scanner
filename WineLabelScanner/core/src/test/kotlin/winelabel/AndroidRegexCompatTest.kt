package winelabel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Android compiles regexes with ICU, not java.util.regex. ICU only supports the inline
 * flags i, m, s, x, w: anything else ((?u), (?U), (?d), ...) compiles on the JVM but throws
 * PatternSyntaxException on a phone. JVM tests can't run ICU, so scan the sources instead.
 */
class AndroidRegexCompatTest {
    @Test
    fun noInlineFlagsUnsupportedByIcu() {
        val inlineFlags = Regex("""\(\?([a-zA-Z]+)(?:-[a-zA-Z]+)?[:)]""")
        val bad = mutableListOf<String>()
        for (file in File("src/main/kotlin").walk().filter { it.extension == "kt" }) {
            for ((i, line) in file.readLines().withIndex()) {
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*")) continue // comments may mention flags
                for (m in inlineFlags.findAll(line)) {
                    if (m.groupValues[1].any { it !in "imsxw" }) bad.add("${file.name}:${i + 1}: ${m.value}")
                }
            }
        }
        assertTrue(bad.isEmpty(), "inline regex flags unsupported on Android: $bad")
    }
}
