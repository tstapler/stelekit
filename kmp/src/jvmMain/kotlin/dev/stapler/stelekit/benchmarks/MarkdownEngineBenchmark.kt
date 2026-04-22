package dev.stapler.stelekit.benchmarks

import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.ui.components.extractSuggestions
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling
import kotlinx.benchmark.*
import org.openjdk.jmh.annotations.Scope

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class MarkdownEngineBenchmark {

    private lateinit var matcher500: AhoCorasickMatcher
    private lateinit var plainText: String
    private lateinit var markdownText: String
    private lateinit var precomputedSpans: List<AhoCorasickMatcher.MatchSpan>

    @Setup
    fun setup() {
        matcher500 = AhoCorasickMatcher((1..500).associate { "topic $it" to "Topic $it" })
        plainText = (1..10).joinToString(". ") { "Notes on topic $it and topic ${it + 1} from today's meeting" }
        markdownText = buildString {
            append("# Heading\n\n")
            append("Some **bold** and _italic_ text with [[Wiki Links]] and `code`.\n\n")
            repeat(5) { i ->
                append("Paragraph $i: Notes on topic ${i + 1} and topic ${i + 2}. ")
                append("See [[Page ${i + 1}]] for details. ")
                append("[Link](https://example.com) and more text.\n\n")
            }
        }
        precomputedSpans = extractSuggestions(plainText, matcher500)
    }

    @Benchmark
    fun extractSuggestionsFromPlainText(): List<AhoCorasickMatcher.MatchSpan> =
        extractSuggestions(plainText, matcher500)

    @Benchmark
    fun parseMarkdownNoSuggestions(): androidx.compose.ui.text.AnnotatedString =
        parseMarkdownWithStyling(
            text = markdownText,
            linkColor = Color.Blue,
            textColor = Color.Black,
        )

    @Benchmark
    fun parseMarkdownWithPrecomputedSpans(): androidx.compose.ui.text.AnnotatedString =
        parseMarkdownWithStyling(
            text = plainText,
            linkColor = Color.Blue,
            textColor = Color.Black,
            suggestionSpans = precomputedSpans,
        )

    @Benchmark
    fun fullPipeline_extractThenParse(): androidx.compose.ui.text.AnnotatedString {
        val spans = extractSuggestions(plainText, matcher500)
        return parseMarkdownWithStyling(
            text = plainText,
            linkColor = Color.Blue,
            textColor = Color.Black,
            suggestionSpans = spans,
        )
    }
}
