package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS/Web storage: holds only the current run and never offers a Resume. */
class InMemoryMergeStorageTest {
    private val src = GraphId("src")
    private val dst = GraphId("dst")

    private fun page(n: Int) = MergePage(name = "Page $n", blocks = listOf(MergeBlock("u$n", "c$n")))

    @Test
    fun `staging round-trips pages in index order and clears on delete`() {
        val staging = InMemoryMergeStorage().staging.create(MergeId("m1"), src, dst, 0L).getOrNull()!!
        staging.writePage(1, page(1))
        staging.writePage(0, page(0))

        assertEquals(2, staging.pageCount())
        assertEquals(listOf(page(0), page(1)), staging.readAll().map { it.getOrNull() }.toList())
        assertEquals(page(1), staging.readPage(1))
        assertNull(staging.readPage(7))

        staging.delete()
        assertEquals(0, staging.pageCount())
    }

    @Test
    fun `a new run replaces the previous staging so only one run is held`() {
        val store = InMemoryMergeStorage().staging
        val first = store.create(MergeId("m1"), src, dst, 0L).getOrNull()!!
        first.writePage(0, page(0))
        store.create(MergeId("m2"), src, dst, 0L).getOrNull()!!

        assertEquals(0, first.pageCount())
        assertNull(store.open(MergeId("m1")))
        assertNotNull(store.open(MergeId("m2")))
    }

    @Test
    fun `an unsafe merge id is rejected like the disk store`() {
        val storage = InMemoryMergeStorage()
        assertTrue(storage.staging.create(MergeId("../x"), src, dst, 0L).isLeft())
        assertTrue(storage.manifests.begin(MergeId("../x"), "s", "d", 0L).isLeft())
    }

    @Test
    fun `manifest tracks the run and an unfinished one is never offered as interrupted`() {
        val manifests = InMemoryMergeStorage().manifests
        val log = manifests.begin(MergeId("m1"), "s", "d", 5L).getOrNull()!!
        log.appendPage(ManifestPageEntry("A", addedBlockUuids = listOf("u1")))

        val inProgress = manifests.load(MergeId("m1"))!!
        assertEquals(MergeStatus.InProgress, inProgress.status)
        assertEquals(listOf("A"), inProgress.pages.map { it.pageName })
        assertTrue(manifests.findInterrupted().isEmpty(), "nothing survives a restart, so there is no Resume here")

        manifests.writerFor(MergeId("m1"))!!.complete()
        assertEquals(MergeStatus.Complete, manifests.load(MergeId("m1"))!!.status)
        manifests.delete(MergeId("m1"))
        assertNull(manifests.load(MergeId("m1")))
    }
}
