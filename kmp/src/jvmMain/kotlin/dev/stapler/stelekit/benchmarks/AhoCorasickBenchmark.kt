package dev.stapler.stelekit.benchmarks

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import kotlinx.benchmark.*
import org.openjdk.jmh.annotations.Scope

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class AhoCorasickBenchmark {

    private lateinit var matcher10: AhoCorasickMatcher
    private lateinit var matcher100: AhoCorasickMatcher
    private lateinit var matcher500: AhoCorasickMatcher
    private lateinit var denseText: String
    private lateinit var sparseText: String

    @Setup
    fun setup() {
        matcher10 = buildMatcher(10)
        matcher100 = buildMatcher(100)
        matcher500 = buildMatcher(500)
        denseText = (1..10).joinToString(". ") { "I reviewed topic $it and related topic ${it + 1}" }
        sparseText = "The quick brown fox jumps over the lazy dog. ".repeat(20)
    }

    @Benchmark
    fun construct10Pages(): AhoCorasickMatcher = buildMatcher(10)

    @Benchmark
    fun construct100Pages(): AhoCorasickMatcher = buildMatcher(100)

    @Benchmark
    fun construct500Pages(): AhoCorasickMatcher = buildMatcher(500)

    @Benchmark
    fun findAllDenseText10Pages(): List<AhoCorasickMatcher.MatchSpan> =
        matcher10.findAll(denseText)

    @Benchmark
    fun findAllDenseText100Pages(): List<AhoCorasickMatcher.MatchSpan> =
        matcher100.findAll(denseText)

    @Benchmark
    fun findAllDenseText500Pages(): List<AhoCorasickMatcher.MatchSpan> =
        matcher500.findAll(denseText)

    @Benchmark
    fun findAllSparseText500Pages(): List<AhoCorasickMatcher.MatchSpan> =
        matcher500.findAll(sparseText)

    private fun buildMatcher(count: Int): AhoCorasickMatcher =
        AhoCorasickMatcher((1..count).associate { "topic $it" to "Topic $it" })
}
