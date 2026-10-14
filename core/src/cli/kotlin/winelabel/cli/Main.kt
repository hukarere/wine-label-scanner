package winelabel.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import winelabel.Scanner
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """Desktop wine-label OCR (same models and pipeline as the app).

  scan <photo> [--json] [--debug-image boxes.png]   text + fields of one photo
  eval <folder> [--only a.jpg,b.jpg] [--dump dir]   accuracy against <folder>/expected.json

Run from the repository root:  ./gradlew -q :core:cli --args="eval fixtures/images""""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val rest = args.drop(1)
    when (cmd) {
        "scan" -> scan(rest)
        "eval" -> eval(rest)
        else -> { System.err.println(USAGE); exitProcess(if (cmd == null || cmd == "-h" || cmd == "--help") 0 else 2) }
    }
}

private fun option(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: usage("$name needs a value") }

private fun positional(args: List<String>): String? {
    val valued = setOf("--debug-image", "--only", "--dump")
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a in valued) i += 2 else if (a.startsWith("--")) i++ else return a
    }
    return null
}

private fun usage(msg: String): Nothing {
    System.err.println("$msg\n\n$USAGE")
    exitProcess(2)
}

private fun scan(args: List<String>) {
    val photo = File(positional(args) ?: usage("scan: which photo?"))
    val result = Desktop.engine().use { Scanner.scan(Desktop.loadImage(photo), it) }
    option(args, "--debug-image")?.let { Desktop.writeDebugImage(result, File(it)) }
    if ("--json" in args) {
        val json = buildJsonObject {
            put("raw_text", result.rawText)
            putJsonObject("fields") {
                for ((name, f) in result.label.fields()) {
                    if (f == null) put(name, JsonNull) else putJsonObject(name) {
                        put("value", (f.value as? List<*>)?.let { l -> JsonArray(l.map { JsonPrimitive(it.toString()) }) }
                            ?: (f.value as? Number)?.let { JsonPrimitive(it) } ?: JsonPrimitive(f.value.toString()))
                        put("confidence", f.confidence)
                        put("source", f.source)
                    }
                }
            }
            putJsonArray("lines") {
                for (l in result.lines) add(buildJsonObject {
                    put("text", l.text)
                    put("confidence", l.confidence)
                    put("height", l.height)
                    put("box", buildJsonArray { for (p in l.box) add(buildJsonArray { add(JsonPrimitive(p.x)); add(JsonPrimitive(p.y)) }) })
                })
            }
            put("millis", result.millis)
        }
        println(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        return
    }
    println("=== Text on label ===")
    println(result.rawText.ifEmpty { "(no text found)" })
    println("\n=== Fields ===")
    for ((name, f) in result.label.fields()) {
        val value = when (val v = f?.value) { null -> "-"; is List<*> -> v.joinToString(", "); else -> v.toString() }
        println("  ${name.padEnd(15)} $value" + (f?.let { "   (%.2f)".format(Locale.ROOT, it.confidence) } ?: ""))
    }
    println("\n=== OCR lines (${result.lines.size}, ${result.millis} ms) ===")
    result.lines.forEachIndexed { i, l ->
        println("  %2d. [%.2f h=%4.0f] %s".format(Locale.ROOT, i + 1, l.confidence, l.height, l.text))
    }
}

private fun eval(args: List<String>) {
    val dir = File(positional(args) ?: usage("eval: which folder?"))
    if (!dir.resolve("expected.json").exists()) usage("eval: no expected.json in $dir")
    val only = option(args, "--only")?.split(",")?.map { it.trim() }?.toSet()
    val report = Desktop.engine().use { Evaluate.run(dir, it, only) }
    option(args, "--dump")?.let { Evaluate.dump(report, File(it)) }
    Evaluate.print(report)
}
