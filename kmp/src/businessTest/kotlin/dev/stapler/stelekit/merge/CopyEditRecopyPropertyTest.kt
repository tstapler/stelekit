package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.ActiveTargetWriterContractTest.ActiveHarness
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Copy, edit copied blocks through the editor, re-copy, copy again, on the real active path:
 * in-memory `GraphManager`, `ActiveTargetWriter`, `GraphWriter`, `GraphLoader`, `BlockStateManager`.
 */
class CopyEditRecopyPropertyTest {
    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val policy = MergePolicy(GraphId("src"), "Source")

    /** Random tree, depth <= 2; labeled blocks get `s<n>` ids, some refer to an earlier labeled block. */
    private fun generate(name: String, rnd: Random): MergePage {
        var n = 0
        val labeled = mutableListOf<String>()
        fun block(depth: Int): MergeBlock {
            val i = n++
            val id = if (rnd.nextInt(4) != 0) "$name-s$i" else null
            val ref = if (id != null && labeled.isNotEmpty() && rnd.nextInt(3) == 0) " ((${labeled.random(rnd)}))" else ""
            if (id != null) labeled += id
            val kids = if (depth < 2) List(rnd.nextInt(3)) { block(depth + 1) } else emptyList()
            return MergeBlock(id, "block $i$ref", children = kids)
        }
        return MergePage(name, blocks = List(1 + rnd.nextInt(5)) { block(0) })
    }

    private fun flat(bs: List<MergeBlock>): List<MergeBlock> = bs.flatMap { listOf(it) + flat(it.children) }

    private fun editedRoots(bs: List<MergeBlock>, edited: Set<String>): Int =
        bs.sumOf { if (it.uuid in edited) 1 else editedRoots(it.children, edited) }

    @Test
    fun copyEditRecopyKeepsIdentityOnTheRealActivePath() = runBlocking {
        val id = GraphId("aaaaaaaaaaaaaaaa")
        val settings = MapSettings()
        settings.putString(
            "graph_registry",
            Json.encodeToString(GraphRegistry(activeGraphId = id, graphs = listOf(GraphInfo(id = id, path = "/data/a", displayName = "a", addedAt = 0L)))),
        )
        val manager = GraphManager(settings, DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY)
        val harness = ActiveHarness(assertNotNull(manager.awaitPendingMigration()), awaitDirtyClear = false)
        var iteration = 0
        try {
            checkAll(50, Arb.long()) { seed ->
                val key = PageKey("Prop${iteration++}")
                val rnd = Random(seed)
                val source = generate(key.name, rnd)

                suspend fun copy(): MergeOutcome {
                    val outcome = mergePage(harness.writer.readExisting(key).fold({ error("$it") }, { it }), source, policy)
                    when (outcome) {
                        is MergeOutcome.New -> harness.writer.write(key, outcome.page)
                        is MergeOutcome.Merged -> harness.writer.write(key, outcome.page)
                        MergeOutcome.Unchanged -> null
                        is MergeOutcome.RefsDidNotConverge -> error("refs did not converge: $outcome")
                    }?.fold({ error("write failed: $it") }, { })
                    return outcome
                }

                assertIs<MergeOutcome.New>(copy())
                val first = assertNotNull(harness.snapshot(key))
                val copied = flat(first.blocks).filter { it.uuid != null }
                val toEdit = copied.filter { rnd.nextInt(3) == 0 }.map { it.uuid!! }.toSet()
                toEdit.forEach { u ->
                    harness.editBlock(key, u, flat(first.blocks).first { it.uuid == u }.content + " edited")
                }

                val afterEdit = assertNotNull(harness.snapshot(key))
                val keyOrder = { b: MergeBlock -> b.properties.keys.toList() }
                flat(afterEdit.blocks).filter { it.uuid != null }.forEach { b ->
                    val original = copied.first { it.uuid == b.uuid }
                    assertEquals(keyOrder(original), keyOrder(b), "editor save dropped or reordered properties of ${b.uuid}")
                    assertEquals(original.properties, b.properties)
                }

                val second = copy()
                val expectedConflicts = editedRoots(first.blocks, toEdit)
                if (expectedConflicts == 0) {
                    assertEquals(MergeOutcome.Unchanged, second, "seed $seed")
                } else {
                    val merged = assertIs<MergeOutcome.Merged>(second, "seed $seed")
                    assertEquals(0, merged.added, "seed $seed: only conflict siblings may be added")
                    assertEquals(expectedConflicts, merged.conflicts.size, "seed $seed")
                }

                val fileAfterSecond = harness.fileText(key)
                assertEquals(MergeOutcome.Unchanged, copy(), "seed $seed: third copy must add nothing")
                assertEquals(fileAfterSecond, harness.fileText(key))

                harness.snapshot(key)
                assertEquals(harness.blockUuids(key), harness.reloadedUuids(key), "seed $seed: DB uuids differ from a fresh GraphLoader parse of the file")
                assertEquals(0, harness.conflicts.get())
            }
        } finally {
            harness.close()
            manager.shutdown()
        }
        assertTrue(iteration >= 50)
    }
}
