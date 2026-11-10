package dev.stapler.stelekit.projectbridge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.property.RandomSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MiniYamlPropertyTest {
    private val letters = ('a'..'z').toList()

    private fun RandomSource.word(): String =
        (1..(1 + random.nextInt(6))).map { letters[random.nextInt(letters.size)] }.joinToString("")

    // A word that is not a YAML keyword, so it stays a plain string.
    private fun RandomSource.plainWord(): String = word().let { if (it == "null" || it == "true" || it == "false") "${it}x" else it }

    private fun RandomSource.scalar(): YamlScalar = when (random.nextInt(5)) {
        0 -> YamlScalar(random.nextInt(-500, 500).toString(), ScalarKind.INT)
        1 -> YamlScalar("${random.nextInt(0, 99)}.${random.nextInt(0, 999)}", ScalarKind.DECIMAL)
        2 -> YamlScalar(plainWord(), ScalarKind.STRING)
        3 -> YamlScalar(if (random.nextBoolean()) "true" else "false", ScalarKind.BOOL)
        else -> YamlScalar("${word()} # ${word()}: ${word()}", ScalarKind.QUOTED)
    }

    private fun RandomSource.node(depth: Int): YamlNode = when {
        depth <= 0 || random.nextInt(3) == 0 -> scalar()
        random.nextBoolean() -> YamlMap(distinctKeys(1 + random.nextInt(3)).map { it to node(depth - 1) })
        else -> YamlList((1..(1 + random.nextInt(3))).map { if (random.nextBoolean()) scalar() else mapNode(depth - 1) })
    }

    private fun RandomSource.mapNode(depth: Int) = YamlMap(distinctKeys(1 + random.nextInt(3)).map { it to node(depth - 1) })

    private fun RandomSource.distinctKeys(n: Int): List<String> {
        val keys = LinkedHashSet<String>()
        while (keys.size < n) keys.add(word())
        return keys.toList()
    }

    private fun renderScalar(s: YamlScalar, comment: String?): String {
        val text = if (s.kind == ScalarKind.QUOTED) "\"${s.text}\"" else s.text
        return if (comment == null) text else "$text  # $comment"
    }

    private fun render(node: YamlNode, indent: Int, out: MutableList<String>, withComments: Boolean) {
        val pad = " ".repeat(indent)
        when (node) {
            is YamlMap -> node.entries.forEach { (k, v) -> renderEntry("$pad$k:", v, indent, out, withComments) }
            is YamlList -> node.items.forEach { item ->
                when (item) {
                    is YamlScalar -> out.add("$pad- ${renderScalar(item, if (withComments) "c" else null)}")
                    is YamlMap -> {
                        val sub = ArrayList<String>()
                        render(item, indent + 2, sub, withComments)
                        out.add("$pad- ${sub.first().trimStart()}")
                        out.addAll(sub.drop(1))
                    }
                    is YamlList -> error("not generated")
                }
            }
            is YamlScalar -> out.add(pad + renderScalar(node, null))
        }
    }

    private fun renderEntry(head: String, v: YamlNode, indent: Int, out: MutableList<String>, withComments: Boolean) {
        when (v) {
            is YamlScalar -> out.add("$head ${renderScalar(v, if (withComments) "note" else null)}")
            else -> {
                out.add(head)
                render(v, indent + 2, out, withComments)
            }
        }
    }

    private fun stripComments(node: YamlNode): YamlNode = when (node) {
        is YamlScalar -> node.copy(comment = null)
        is YamlMap -> YamlMap(node.entries.map { it.first to stripComments(it.second) })
        is YamlList -> YamlList(node.items.map(::stripComments))
    }



    @Test
    fun renderThenParse_should_RoundTripGeneratedDocuments() = runTest {
        checkAll(arbitrary { rs -> rs.mapNode(3) }, Arb.int(0..1)) { doc, comments ->
            val lines = ArrayList<String>()
            render(doc, 0, lines, withComments = comments == 1)
            val parsed = MiniYaml.parse(lines.joinToString("\n"))
            assertIs<MiniYamlResult.Parsed>(parsed, lines.joinToString("\n"))
            assertEquals(stripComments(doc), stripComments(parsed.root), lines.joinToString("\n"))
        }
    }

    @Test
    fun parse_should_NeverThrowOnArbitraryText() = runTest {
        checkAll(Arb.string(0..200)) { text -> MiniYaml.parse(text) }
        checkAll(Arb.string(0..40), Arb.string(0..40), Arb.string(0..40)) { a, b, c ->
            MiniYaml.parse("$a\n  $b\n- $c\n    $a: $b")
        }
    }

    @Test
    fun flowListOfScalars_should_ParseAsAList() {
        val root = assertIs<MiniYamlResult.Parsed>(MiniYaml.parse("c: [0.8, 0.65, x]")).root as YamlMap
        assertEquals(listOf("0.8", "0.65", "x"), (root.entries.single().second as YamlList).items.map { (it as YamlScalar).text })
    }

    @Test
    fun trailingComment_should_BeCapturedPerScalar() {
        val parsed = MiniYaml.parse("width: 136   # wall to wall\nname: \"a # b\" # real")
        val root = assertIs<MiniYamlResult.Parsed>(parsed).root as YamlMap
        assertEquals(YamlScalar("136", ScalarKind.INT, "wall to wall"), root.entries[0].second)
        assertEquals(YamlScalar("a # b", ScalarKind.QUOTED, "real"), root.entries[1].second)
    }

    @Test
    fun unsupportedConstructs_should_ReportTheirLine() {
        val cases = mapOf(
            "a: &x 1" to 1,
            "a: 1\nb: *x" to 2,
            "a: !!str 1" to 1,
            "a: [1, [2]]" to 1,
            "a: {b: 1}" to 1,
            "a: |\n  text" to 1,
            "a: 1\n---\nb: 2" to 2,
        )
        cases.forEach { (text, line) ->
            assertEquals(MiniYamlResult.Unsupported(line), MiniYaml.parse(text), text)
        }
    }

    @Test
    fun tabsAndBadIndentation_should_BeMalformed() {
        assertIs<MiniYamlResult.Malformed>(MiniYaml.parse("a:\n\tb: 1"))
        assertIs<MiniYamlResult.Malformed>(MiniYaml.parse("a: 1\n  b: 2"))
        assertIs<MiniYamlResult.Malformed>(MiniYaml.parse("a: \"open"))
    }
}
