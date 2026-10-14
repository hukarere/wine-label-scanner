package winelabel.parse

/** A reference name: [display] is reported, [key] (normalized) is matched, [extra] e.g. a region's country. */
data class RefName(val display: String, val key: String, val extra: String = "")

/** Grape / region / country lists, loaded from resources/winelabel/data. */
object Reference {
    val grapes: List<RefName> by lazy {
        sortedNames(dataLines("grapes.txt").map { RefName(it, Text.normalize(it)) })
    }

    val regions: List<RefName> by lazy {
        sortedNames(dataLines("regions.txt").map { ln ->
            val (name, country) = ln.split("|")
            RefName(name, Text.normalize(name), country)
        })
    }

    val countries: List<RefName> by lazy {
        sortedNames(dataLines("countries.txt").flatMap { ln ->
            val names = ln.split("|")
            names.map { alias -> RefName(names[0], Text.normalize(alias)) }
        })
    }

    // Longest names first so "Cabernet Sauvignon" claims its words before "Sauvignon".
    private fun sortedNames(names: List<RefName>) =
        names.sortedWith(compareBy({ -it.key.split(" ").size }, { -it.key.length }))

    private fun dataLines(name: String): List<String> {
        val stream = Reference::class.java.getResourceAsStream("/winelabel/data/$name")
            ?: error("missing resource $name")
        return stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim() }
    }
}
