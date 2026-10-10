package dev.stapler.stelekit.merge

import java.io.File
import kotlin.test.Test

/**
 * Pass rate of the production [RoundTripGuard.probe] over a real graph (read-only); the spike
 * measured 99.68% for its stand-in splicer. Skipped unless SPIKE_GRAPH_PATH is set.
 */
class RoundTripGuardRealGraphTest {
    @Test
    fun measure() {
        val root = System.getenv("SPIKE_GRAPH_PATH")?.takeIf { it.isNotBlank() }
        if (root == null) {
            println("SKIPPED RoundTripGuardRealGraphTest: set SPIKE_GRAPH_PATH to a graph directory")
            return
        }
        val files = listOf("pages", "journals").flatMap { sub ->
            File(root, sub).listFiles { f -> f.isFile && f.name.endsWith(".md") }?.toList().orEmpty()
        }
        val failures = files.mapNotNull { f ->
            val text = String(f.readBytes(), Charsets.UTF_8)
            RoundTripGuard.probe(text, f.path, f.parentFile.name == "journals").leftOrNull()?.let { f to it }
        }
        val pass = files.size - failures.size
        println("GUARD-PASS-RATE: $pass/${files.size} = ${"%.2f".format(pass * 100.0 / files.size)}%")
        failures.groupingBy { it.second::class.simpleName }.eachCount().forEach { (k, v) -> println("GUARD-FAIL-KIND: $k=$v") }
        failures.take(12).forEach { (f, why) ->
            println("GUARD-FAIL-SAMPLE: ${f.name.take(3)}*** ${why.message.take(110).replace("\n", "\\n").replace("\t", "\\t")}")
        }
    }
}
