package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidRemapTest {
    private val g = GraphId("g-work")
    private val uuidShape = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val arbUuid = Arb.uuid().map { it.toString() }

    @Test
    fun remapIsDeterministicAndRecordsSrcId() {
        val first = UuidRemap.uuidFor(g, "S1")
        assertEquals(first, UuidRemap.uuidFor(g, "S1"))
        val block = MergeBlock("S1", "x")
        assertEquals(UuidRemap.compute(g, listOf(block)), UuidRemap.compute(g, listOf(block)))
        assertEquals("g-work:S1", SourceBlockRef.of(g, "S1").value)
        assertEquals("S1", SourceBlockRef.of(g, "S1").sourceUuid)
    }

    @Test
    fun uuidPrimeIsSha256TruncatedTo128BitsFormattedAsUuid() {
        val hex = "merge:g-work:S1".encodeUtf8().sha256().hex().take(32)
        val expected = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        assertEquals(expected, UuidRemap.uuidFor(g, "S1"))
        assertTrue(uuidShape.matches(expected))
    }

    @Test
    fun computeRemapsEveryNestedBlockWithoutATargetOracle() {
        val tree = listOf(MergeBlock("a", "1", children = listOf(MergeBlock("b", "2"))), MergeBlock(null, "3"))
        val map = UuidRemap.compute(g, tree)
        assertEquals(setOf("a", "b"), map.keys)
        map.forEach { (s, p) -> assertNotEquals(s, p) }
    }

    @Test
    fun noCollisionsAcross10000DistinctPairs() {
        val outputs = HashSet<String>()
        repeat(10_000) { i -> outputs += UuidRemap.uuidFor(GraphId("g-${i % 7}"), "src-$i") }
        assertEquals(10_000, outputs.size)
    }

    @Test
    fun conflictUuidDiffersFromPlainRemapAndFromOtherContent() = runTest {
        checkAll(200, arbUuid) { s ->
            val plain = UuidRemap.uuidFor(g, s)
            val c1 = UuidRemap.conflictUuid(g, s, "Draft v1")
            assertNotEquals(plain, c1)
            assertNotEquals(c1, UuidRemap.conflictUuid(g, s, "Draft v2"))
            assertEquals(c1, UuidRemap.conflictUuid(g, s, "Draft v1"))
            assertNotEquals(s, plain)
        }
    }

    @Test
    fun refsAndEmbedsAreRewrittenAndUnselectedRefsKept() {
        val map = mapOf("S2" to UuidRemap.uuidFor(g, "S2"))
        val p2 = map.getValue("S2")
        assertEquals(
            "see (($p2)) and {{embed (($p2))}} and ((OUT))",
            UuidRemap.rewriteRefs("see ((S2)) and {{embed ((S2))}} and ((OUT))", map),
        )
    }

    @Test
    fun rewriteIsIdempotentAndDeterministic() = runTest {
        checkAll(200, Arb.list(arbUuid, 1..4), Arb.list(Arb.element("see", "((", "))", " ", "x"), 0..6)) { ids, noise ->
            val map = ids.associateWith { UuidRemap.uuidFor(g, it) }
            val content = ids.joinToString(" ") { "(($it))" } + noise.joinToString("") + "((OUT))"
            val once = UuidRemap.rewriteRefs(content, map)
            assertEquals(once, UuidRemap.rewriteRefs(content, map))
            assertEquals(once, UuidRemap.rewriteRefs(once, map))
        }
    }
}
