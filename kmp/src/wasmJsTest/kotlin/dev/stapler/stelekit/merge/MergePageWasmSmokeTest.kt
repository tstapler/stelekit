package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `mergePage` (SHA-256 uuid remap, pure Kotlin) runs on the wasmJs runtime: new, unchanged, then combined. */
class MergePageWasmSmokeTest {
    private val policy = MergePolicy(GraphId("aaaaaaaaaaaaaaaa"), "Source")
    private val incoming = MergePage(
        name = "Smoke",
        blocks = listOf(MergeBlock("11111111-1111-1111-1111-111111111111", "one", children = listOf(MergeBlock(null, "child")))),
    )

    @Test
    fun newPageGetsRemappedUuidAndSourceRef() {
        val created = assertIs<MergeOutcome.New>(mergePage(null, incoming, policy)).page
        val top = created.blocks.single()
        assertNotNull(top.uuid)
        assertEquals(false, top.uuid == "11111111-1111-1111-1111-111111111111", "uuid is always remapped")
        assertNotNull(top.properties[MergePropertyKeys.SRC_ID])
        assertNull(top.children.single().uuid)
    }

    @Test
    fun repeatIsUnchangedAndNewSiblingIsCombined() {
        val created = assertIs<MergeOutcome.New>(mergePage(null, incoming, policy)).page
        assertIs<MergeOutcome.Unchanged>(mergePage(created, incoming, policy))

        val more = incoming.copy(blocks = incoming.blocks + MergeBlock("22222222-2222-2222-2222-222222222222", "two"))
        val merged = assertIs<MergeOutcome.Merged>(mergePage(created, more, policy))
        assertEquals(2, merged.page.blocks.size)
    }
}
