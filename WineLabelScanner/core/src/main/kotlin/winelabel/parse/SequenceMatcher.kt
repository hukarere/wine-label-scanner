package winelabel.parse

/**
 * Port of Python's difflib.SequenceMatcher(None, a, b).ratio() so fuzzy scores
 * match the prototype exactly. No autojunk: inputs here are far below 200 chars.
 */
object SequenceMatcher {
    fun ratio(a: String, b: String): Double {
        val total = a.length + b.length
        return if (total == 0) 1.0 else 2.0 * matchingChars(a, b) / total
    }

    private fun matchingChars(a: String, b: String): Int {
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() }.add(j) }
        var matched = 0
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = longestMatch(a, b2j, alo, ahi, blo, bhi)
            if (k > 0) {
                matched += k
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return matched
    }

    private fun longestMatch(
        a: String, b2j: Map<Char, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int,
    ): Triple<Int, Int, Int> {
        var besti = alo
        var bestj = blo
        var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newJ2len = HashMap<Int, Int>()
            for (j in b2j[a[i]] ?: emptyList()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newJ2len[j] = k
                if (k > bestSize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestSize = k
                }
            }
            j2len = newJ2len
        }
        return Triple(besti, bestj, bestSize)
    }
}
