package dev.stapler.stelekit.merge

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeManifestTest {
    private val fs = FakeFileSystem()
    private val store = MergeManifestStore(fs, "/app")
    private val day = 24L * 60 * 60 * 1000

    private val created = ManifestPageEntry("New", createdFiles = listOf(CreatedFile("pages/New.md", "H1")))
    private val added = ManifestPageEntry("Projects", addedBlockUuids = listOf("u1", "u2"))

    @Test
    fun recordAndReload() {
        val w = store.begin(MergeId("m1"), "src", "dst", 5L).getOrNull()!!
        w.appendPage(created)
        w.appendPage(added)
        w.complete()

        val m = store.load(MergeId("m1"))!!
        assertEquals(MergeStatus.Complete, m.status)
        assertEquals(listOf(created, added), m.pages)
        assertEquals(MergeManifest("m1", "src", "dst", 5L, MergeStatus.Complete, listOf(created, added)), m)
    }

    @Test
    fun flushIsPerPageSoCrashKeepsEarlierPages() {
        val w = store.begin(MergeId("m2"), "s", "t", 1L).getOrNull()!!
        w.appendPage(created)
        // Simulated crash: no complete(), and a torn trailing line.
        val path = "/app/.stele-merge-manifests/m2.jsonl".toPath()
        val before = fs.read(path) { readUtf8() }
        fs.write(path) { writeUtf8(before + "{\"type\":\"page\",\"entry\":{\"pageNa") }

        val m = store.load(MergeId("m2"))!!
        assertEquals(MergeStatus.InProgress, m.status)
        assertEquals(listOf(created), m.pages)
    }

    @Test
    fun findInterruptedReturnsOnlyInProgress() {
        store.begin(MergeId("done"), "s", "t", 1L).getOrNull()!!.complete()
        store.begin(MergeId("cut"), "s", "t", 2L).getOrNull()!!.appendPage(added)

        assertEquals(listOf("cut"), store.findInterrupted().map { it.mergeId })
    }

    @Test
    fun listDeleteAndExpire() {
        val now = 100 * day
        store.begin(MergeId("a"), "s", "t", now - 8 * day)
        store.begin(MergeId("b"), "s", "t", now - 2 * day)
        assertEquals(listOf("a", "b"), store.list().map { it.mergeId })

        store.expire(now)
        assertEquals(listOf("b"), store.list().map { it.mergeId })

        store.delete(MergeId("b"))
        assertNull(store.load(MergeId("b")))
        assertTrue(store.delete(MergeId("b")).isRight())
    }

    @Test
    fun corruptManifestIsIgnoredAndNeverExpired() {
        fs.createDirectories("/app/.stele-merge-manifests".toPath())
        val p = "/app/.stele-merge-manifests/junk.jsonl".toPath()
        fs.write(p) { writeUtf8("nope") }
        assertTrue(store.list().isEmpty())
        store.expire(1000 * day)
        assertTrue(fs.exists(p))
    }

    @Test
    fun fileNamesComeFromMergeIdOnlyAndAreContained() {
        for (bad in listOf("../x", "a/b", "..", "", ".")) {
            assertTrue(store.begin(MergeId(bad), "s", "t", 0).isLeft(), "rejects '$bad'")
            assertNull(store.load(MergeId(bad)))
        }
        // Page names (even hostile ones) live only inside the JSON body.
        val w = store.begin(MergeId("ok"), "s", "t", 0).getOrNull()!!
        w.appendPage(ManifestPageEntry("../../etc/passwd"))
        assertEquals(listOf("ok.jsonl"), fs.list("/app/.stele-merge-manifests".toPath()).map { it.name })
        assertEquals("../../etc/passwd", store.load(MergeId("ok"))!!.pages.single().pageName)
    }
}
