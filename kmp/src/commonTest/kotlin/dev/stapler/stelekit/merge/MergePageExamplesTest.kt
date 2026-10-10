package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergePageExamplesTest {
    private val g = GraphId("g")
    private val policy = MergePolicy(sourceGraphId = g, sourceGraphName = "Work")

    private fun b(uuid: String?, content: String, vararg children: MergeBlock, props: Map<String, String> = emptyMap()) =
        MergeBlock(uuid, content, props, children.toList())

    private fun page(vararg blocks: MergeBlock, props: Map<String, String> = emptyMap()) =
        MergePage(name = "Projects", properties = props, blocks = blocks.toList())

    private fun merged(existing: MergePage?, incoming: MergePage) = mergePage(existing, incoming, policy)

    private fun MergeOutcome.mergedPage(): MergeOutcome.Merged = assertIs<MergeOutcome.Merged>(this)

    @Test
    fun unionPreservesTargetOrder() {
        val out = merged(
            page(b("a1", "A"), b("b1", "B")),
            page(b("b1", "B"), b("c1", "C")),
        ).mergedPage()
        assertEquals(listOf("A", "B", "C"), out.page.blocks.map { it.content })
        assertEquals(1, out.added)
        assertTrue(out.conflicts.isEmpty())
        val c = out.page.blocks[2]
        assertEquals(UuidRemap.uuidFor(g, "c1"), c.uuid)
        assertEquals("g:c1", c.properties[MergePropertyKeys.SRC_ID])
    }

    @Test
    fun sameContentDifferentUuidDedups() {
        assertEquals(MergeOutcome.Unchanged, merged(page(b("t1", "Buy milk")), page(b("s1", "Buy milk"))))
    }

    @Test
    fun sameUuidDifferentContentIsFlaggedSibling() {
        val out = merged(page(b("x1", "Draft v1")), page(b("x1", "Draft v2"))).mergedPage()
        assertEquals(listOf("Draft v1", "Draft v2"), out.page.blocks.map { it.content })
        assertEquals("x1", out.page.blocks[0].uuid)
        val sibling = out.page.blocks[1]
        assertNotEquals("x1", sibling.uuid)
        assertEquals("true", sibling.properties[MergePropertyKeys.CONFLICT])
        assertEquals("Work", sibling.properties[MergePropertyKeys.CONFLICT_SOURCE])
        assertEquals(1, out.conflicts.size)
        assertEquals(BlockConflict("x1", sibling.uuid!!, "Projects"), out.conflicts[0])
        // repeat is a no-op
        assertEquals(MergeOutcome.Unchanged, merged(out.page, page(b("x1", "Draft v2"))))
    }

    @Test
    fun editedAfterCopyThenRecopiedAddsOneStableConflict() {
        val source = page(b("S", "Draft v1"))
        val first = assertIs<MergeOutcome.New>(merged(null, source)).page
        val copy = first.blocks.single()
        assertEquals("g:S", copy.properties[MergePropertyKeys.SRC_ID])
        val edited = first.copy(blocks = listOf(copy.copy(content = "Draft v1 edited")))

        val second = merged(edited, source).mergedPage()
        assertEquals("Draft v1 edited", second.page.blocks[0].content)
        assertEquals(copy.uuid, second.page.blocks[0].uuid)
        assertEquals(1, second.conflicts.size)
        val sibling = second.page.blocks[1]
        assertEquals("Draft v1", sibling.content)
        assertEquals("g:S", sibling.properties[MergePropertyKeys.SRC_ID])
        assertEquals("true", sibling.properties[MergePropertyKeys.CONFLICT])
        assertEquals(UuidRemap.conflictUuid(g, "S", "Draft v1"), sibling.uuid)
        assertNotEquals(copy.uuid, sibling.uuid)

        assertEquals(MergeOutcome.Unchanged, merged(second.page, source))

        val drifted = page(b("S", "Draft v2"))
        val fourth = merged(second.page, drifted).mergedPage()
        assertEquals(1, fourth.conflicts.size)
        assertEquals(3, fourth.page.blocks.size)
        assertEquals(
            UuidRemap.conflictUuid(g, "S", "Draft v2"),
            fourth.page.blocks.single { it.content == "Draft v2" }.uuid,
        )
        assertEquals(MergeOutcome.Unchanged, merged(fourth.page, drifted))
    }

    @Test
    fun unlabeledTargetBlocksKeepTheirIndexWhenAppending() {
        // Flat: A, B unlabeled; the new block follows A in the source but must land after B.
        val flat = merged(page(b(null, "A"), b(null, "B")), page(b(null, "A"), b("n1", "N"))).mergedPage()
        assertEquals(listOf("A", "B", "N"), flat.page.blocks.map { it.content })

        // Nested: new child goes after the last existing child, parent index untouched.
        val nested = merged(
            page(b(null, "P", b(null, "c1"), b(null, "c2")), b(null, "Q")),
            page(b(null, "P", b("n1", "new"))),
        ).mergedPage()
        assertEquals(listOf("P", "Q"), nested.page.blocks.map { it.content })
        assertEquals(listOf("c1", "c2", "new"), nested.page.blocks[0].children.map { it.content })

        // Mixed: a trailing unlabeled block forces append even if a labeled one follows the anchor.
        val mixed = merged(
            page(b("a1", "A"), b("b1", "B"), b(null, "C")),
            page(b("a1", "A"), b("n1", "N")),
        ).mergedPage()
        assertEquals(listOf("A", "B", "C", "N"), mixed.page.blocks.map { it.content })
    }

    @Test
    fun fullyLabeledTailAllowsPlacementAfterAnchor() {
        val out = merged(
            page(b("a1", "A"), b("b1", "B", b("b2", "B-child"))),
            page(b("a1", "A"), b("n1", "N")),
        ).mergedPage()
        assertEquals(listOf("A", "N", "B"), out.page.blocks.map { it.content })
    }

    @Test
    fun propertiesUnionAndScalarClashKeepsTarget() {
        val out = merged(
            page(b("a1", "A"), props = mapOf("tags" to "a", "status" to "open")),
            page(b("a1", "A"), props = mapOf("tags" to "b", "status" to "done", "id" to "ignored")),
        ).mergedPage()
        assertEquals(mapOf("tags" to "a, b", "status" to "open"), out.page.properties)
        assertEquals(listOf("status"), out.propertyClashes)
        assertEquals(0, out.added)
    }

    @Test
    fun shortContentUnderDifferentParentsBothSurvive() {
        val out = merged(
            page(b("p1", "Parent1", b("t1", "TODO"))),
            page(b("p2", "Parent2", b("t2", "TODO"))),
        ).mergedPage()
        assertEquals(listOf("Parent1", "Parent2"), out.page.blocks.map { it.content })
        assertEquals("TODO", out.page.blocks[0].children.single().content)
        assertEquals("TODO", out.page.blocks[1].children.single().content)
        assertEquals(2, out.added)
    }

    @Test
    fun absentExistingPageIsNewWithRemappedUuids() {
        val out = assertIs<MergeOutcome.New>(
            merged(null, page(b("s1", "see ((s2))", b("s2", "kid")))),
        )
        val parent = out.page.blocks.single()
        val kid = parent.children.single()
        assertEquals(UuidRemap.uuidFor(g, "s1"), parent.uuid)
        assertEquals(UuidRemap.uuidFor(g, "s2"), kid.uuid)
        assertEquals("see ((${kid.uuid}))", parent.content)
        assertNull(parent.properties["id"])
    }

    @Test
    fun selfMergeAndEmptySourceAreUnchanged() {
        val t = page(b("a1", "A", b("a2", "B")), b(null, "C"))
        assertEquals(MergeOutcome.Unchanged, merged(t, t))
        assertEquals(MergeOutcome.Unchanged, merged(t, page()))
    }

    @Test
    fun repeatCopyIsIdempotent() {
        val t = page(b("a1", "A"))
        val s = page(b("s1", "X", b("s2", "Y")), b("s3", "Z"))
        val once = merged(t, s).mergedPage()
        assertEquals(MergeOutcome.Unchanged, merged(once.page, s))
    }
}
