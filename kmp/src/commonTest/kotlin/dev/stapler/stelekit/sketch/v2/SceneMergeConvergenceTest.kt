// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two simulated devices share one file and run random edit streams with random sync points, using the saver's protocol: a save
 * writes when the file is still the one the device last saw, merges when it changed, and the editor takes the merge back
 * (`merge(ours, current, merged)`). After the streams stop and both devices sync, every byte must agree and no element that was
 * added and never deleted may be missing.
 */
class SceneMergeConvergenceTest {
    private class Disk(var bytes: ByteArray)

    private class Device(val name: String, val disk: Disk, initial: SketchDocumentV2) {
        var base: SketchDocumentV2 = initial
        var baseBytes: ByteArray = disk.bytes
        var current: SketchDocumentV2 = initial
        var serial = 0
        val added = LinkedHashSet<String>()
        val deleted = LinkedHashSet<String>()

        fun edit(r: Random) {
            val before = SceneMergeSupport.ids(current)
            current = SceneMergeSupport.randomEdit(current, r, name, serial++)
            val after = SceneMergeSupport.ids(current)
            added += after - before
            deleted += before - after
        }

        /** The saver's save: write when the file is unchanged, merge when it changed. */
        fun save() {
            val mine = SheetCodecV2.encode(current).getOrNull()!!
            if (disk.bytes.contentEquals(baseBytes)) {
                if (!mine.contentEquals(disk.bytes)) disk.bytes = mine
                baseBytes = disk.bytes
                base = SheetCodecV2.decode(baseBytes).getOrNull()!!
                return
            }
            val theirs = SheetCodecV2.decode(disk.bytes).getOrNull()!!
            val merged = SceneMerge.merge(base, SheetCodecV2.decode(mine).getOrNull()!!, theirs).getOrNull()!!.doc
            val written = SheetCodecV2.encode(merged).getOrNull()!!
            disk.bytes = written
            baseBytes = written
            base = SheetCodecV2.decode(written).getOrNull()!!
            val saved = SheetCodecV2.decode(mine).getOrNull()!!
            current = SceneMerge.merge(saved, current, base).getOrNull()!!.doc
        }

        /** A device with nothing unsaved adopts the file ("Use theirs"). */
        fun refresh() {
            if (disk.bytes.contentEquals(baseBytes)) return
            baseBytes = disk.bytes
            base = SheetCodecV2.decode(baseBytes).getOrNull()!!
            current = base
        }

        fun unsaved(): Boolean = !SheetCodecV2.encode(current).getOrNull()!!.contentEquals(baseBytes)
    }

    @Test
    fun devices_should_ConvergeToIdenticalBytes_When_EditingConcurrentlyAndSyncing() = runTest {
        checkAll(PropTestConfig(iterations = ROUNDS), Arb.long()) { seed ->
            val r = Random(seed)
            val start = SceneMergeSupport.baseDoc(r, r.nextInt(MAX_BASE) + 1)
            val disk = Disk(SheetCodecV2.encode(start).getOrNull()!!)
            val a = Device("a", disk, start)
            val b = Device("b", disk, start)
            val devices = listOf(a, b)
            repeat(STEPS) {
                val d = devices[r.nextInt(devices.size)]
                when (r.nextInt(STEP_KINDS)) {
                    0, 1, 2 -> d.edit(r)
                    3 -> d.save()
                    else -> if (d.unsaved()) d.save() else d.refresh()
                }
            }
            repeat(SETTLE) {
                for (d in devices) if (d.unsaved()) d.save() else d.refresh()
            }
            val final = String(disk.bytes)
            assertEquals(final, SceneMergeSupport.bytes(a.current), "device a diverged, seed $seed")
            assertEquals(final, SceneMergeSupport.bytes(b.current), "device b diverged, seed $seed")
            val finalIds = SceneMergeSupport.ids(SheetCodecV2.decode(disk.bytes).getOrNull()!!)
            val survivors = (a.added + b.added) - (a.deleted + b.deleted)
            assertTrue(finalIds.containsAll(survivors), "an element that was added and never deleted is missing, seed $seed")
        }
    }

    private companion object {
        const val ROUNDS = 300
        const val STEPS = 40
        const val SETTLE = 4
        const val MAX_BASE = 6
        const val STEP_KINDS = 6
    }
}
