package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeStagingDirectoryTest {
    private val fs = FakeFileSystem()
    private val app = "/app"
    private val day = 24L * 60 * 60 * 1000
    private val src = GraphId("src")
    private val dst = GraphId("dst")

    private fun create(id: String, startedAt: Long = 0L) =
        MergeStagingDirectory.create(fs, app, MergeId(id), src, dst, startedAt).getOrNull()!!

    private fun page(n: Int) = MergePage(name = "Page $n/../x", blocks = listOf(MergeBlock("u$n", "c$n")))

    @Test
    fun layoutAndMarker() {
        val d = create("m1", startedAt = 42)
        d.writePage(0, page(0))
        d.writePage(1, page(1))
        assertTrue(fs.exists("/app/.stele-merge-staging-m1/.marker".toPath()))
        assertTrue(fs.exists("/app/.stele-merge-staging-m1/0.json".toPath()))
        assertTrue(fs.exists("/app/.stele-merge-staging-m1/1.json".toPath()))
        val marker = fs.read("/app/.stele-merge-staging-m1/.marker".toPath()) { readUtf8() }
        for (field in listOf("\"mergeId\":\"m1\"", "\"sourceGraphId\":\"src\"", "\"targetGraphId\":\"dst\"", "\"startedAtEpochMs\":42")) {
            assertTrue(marker.contains(field), "$field in $marker")
        }
        assertEquals(page(1), MergeStagingDirectory.open(fs, app, MergeId("m1"))!!.readAll().toList()[1].getOrNull())
    }

    @Test
    fun sweepDeletesOnlyOldMarkedDirs() {
        val now = 100 * day
        create("old", startedAt = now - 8 * day)
        create("fresh", startedAt = now - 1 * day)
        fs.createDirectories("/app/.stele-merge-staging-nomarker".toPath())
        fs.write("/app/.stele-merge-staging-nomarker/0.json".toPath()) { writeUtf8("{}") }
        fs.createDirectories("/app/.stele-merge-staging-badmarker".toPath())
        fs.write("/app/.stele-merge-staging-badmarker/.marker".toPath()) { writeUtf8("not json") }

        MergeStagingDirectory.sweep(fs, app, now)

        assertFalse(fs.exists("/app/.stele-merge-staging-old".toPath()))
        assertTrue(fs.exists("/app/.stele-merge-staging-fresh".toPath()))
        assertTrue(fs.exists("/app/.stele-merge-staging-nomarker".toPath()))
        assertTrue(fs.exists("/app/.stele-merge-staging-badmarker".toPath()))
    }

    @Test
    fun startupHookSweepsStagingAndExpiredManifests() {
        val now = 100 * day
        create("old", startedAt = now - 8 * day)
        val store = MergeManifestStore(fs, app)
        store.begin(MergeId("oldm"), "s", "t", now - 8 * day)
        store.begin(MergeId("newm"), "s", "t", now - day)

        sweepMergeArtifacts(fs, app, now)

        assertFalse(fs.exists("/app/.stele-merge-staging-old".toPath()))
        assertEquals(listOf("newm"), store.list().map { it.mergeId })
    }

    @Test
    fun readAllHoldsOnePageAtATime() {
        val d = create("big")
        val n = 5000
        // Reuse one tiny page body; the point is file count, not content.
        for (i in 0 until n) d.writePage(i, MergePage(name = "p"))
        val counting = CountingFileSystem(fs)
        val reader = MergeStagingDirectory.open(counting, app, MergeId("big"))!!
        counting.reads = 0

        val seq = reader.readAll()
        assertEquals(0, counting.pageReads(), "sequence must be lazy")
        var seen = 0
        for (result in seq) {
            seen++
            assertTrue(result.isRight())
            assertEquals(seen, counting.pageReads(), "exactly one page read per element visited")
        }
        assertEquals(n, seen)
    }

    @Test
    fun readAllOrdersNumericallyAndReportsCorruptPage() {
        val d = create("ord")
        for (i in listOf(10, 2, 1)) d.writePage(i, page(i))
        fs.write("/app/.stele-merge-staging-ord/2.json".toPath()) { writeUtf8("garbage") }
        val results = d.readAll().toList()
        assertEquals(listOf(true, false, true), results.map { it.isRight() })
        assertEquals("Page 10/../x", results[2].getOrNull()!!.name)
    }

    @Test
    fun namesAreDerivedFromIdsAndContained() {
        val d = create("m2")
        // Page names with traversal never reach the filesystem: file names come from the index.
        d.writePage(0, MergePage(name = "../../etc/passwd"))
        assertEquals(listOf(".marker", "0.json"), fs.list(d.dir).map { it.name }.sortedBy { it != ".marker" })
        assertTrue(d.pagePath(-1).isLeft())
        for (bad in listOf("../evil", "a/b", "..", "", "a\\b", "x\u0000y", ".")) {
            assertTrue(
                MergeStagingDirectory.create(fs, app, MergeId(bad), src, dst, 0).isLeft(),
                "rejects MergeId '$bad'",
            )
        }
        assertFalse(fs.exists("/evil".toPath()))
        val p = d.pagePath(7).getOrNull()!!
        assertTrue(dev.stapler.stelekit.db.PageFileResolver.isWithin(d.dir.toString(), p.toString()))
    }

    private class CountingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var reads = 0
        fun pageReads() = reads

        override fun source(file: Path) = super.source(file).also {
            if (file.name.endsWith(".json")) reads++
        }
    }
}
