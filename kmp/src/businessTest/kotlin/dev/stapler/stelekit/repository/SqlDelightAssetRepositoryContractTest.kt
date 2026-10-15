@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises every public method of [SqlDelightAssetRepository] against a real in-memory SQLite driver. */
class SqlDelightAssetRepositoryContractTest {

    private var driver: SqlDriver? = null

    private fun repo(): SqlDelightAssetRepository {
        val d = DriverFactory().createDriver("jdbc:sqlite::memory:")
        driver = d
        return SqlDelightAssetRepository(SteleDatabase(d))
    }

    /** No repository API sets is_orphan (it is written by the orphan scanner), so tests flag rows directly. */
    private fun markOrphan(uuid: String) {
        driver!!.execute(null, "UPDATE asset_index SET is_orphan = 1 WHERE uuid = ?", 1) { bindString(0, uuid) }
    }

    private fun entry(
        uuid: String,
        mediaType: AssetMediaType = AssetMediaType.IMAGE,
        path: String = "/g/assets/$uuid.bin",
        size: Long = 100L,
        importedAt: Long = 1000L,
        tags: List<String> = emptyList(),
        isOrphan: Boolean = false,
    ) = AssetEntry(
        uuid = AssetUuid(uuid),
        filePath = path,
        relativePath = "../assets/$uuid.bin",
        mediaType = mediaType,
        subfolder = "assets",
        tags = tags,
        autoLabels = emptyList(),
        ocrText = null,
        cloudDescription = null,
        pageUuids = emptyList(),
        sizeBytes = size,
        importedAtMs = importedAt,
        mlProcessed = false,
        mlAttemptedAt = null,
        mlFailed = false,
        contentHash = null,
        isOrphan = false,
        mlTagsSource = "NONE",
    )

    private suspend fun SqlDelightAssetRepository.get(uuid: String) =
        getAssetByUuid(AssetUuid(uuid)).first().getOrNull()

    @Test
    fun `save and get round-trips persisted fields and applies insert defaults`() = runBlocking<Unit> {
        val r = repo()
        val e = entry("a1", AssetMediaType.PDF, tags = listOf("x", "y"))
            .copy(autoLabels = listOf("l"), ocrText = "ocr", cloudDescription = "desc",
                pageUuids = listOf("p1"), contentHash = "h")
        assertTrue(r.saveAsset(e).isRight())
        assertEquals(e, r.get("a1"))
    }

    @Test
    fun `get unknown uuid returns null`() = runBlocking<Unit> {
        assertNull(repo().get("missing"))
    }

    @Test
    fun `getAssets paginates and countAssets counts`() = runBlocking<Unit> {
        val r = repo()
        repeat(5) { r.saveAsset(entry("u$it", importedAt = it.toLong())) }
        assertEquals(5L, r.countAssets().getOrNull())
        val p1 = r.getAssets(limit = 3, offset = 0).first().getOrNull()!!
        val p2 = r.getAssets(limit = 3, offset = 3).first().getOrNull()!!
        assertEquals(listOf("u4", "u3", "u2"), p1.map { it.uuid.value })
        assertEquals(listOf("u1", "u0"), p2.map { it.uuid.value })
    }

    @Test
    fun `getAssetsByMediaType filters`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("img", AssetMediaType.IMAGE))
        r.saveAsset(entry("pdf", AssetMediaType.PDF))
        val pdfs = r.getAssetsByMediaType(AssetMediaType.PDF, 10, 0).first().getOrNull()!!
        assertEquals(listOf("pdf"), pdfs.map { it.uuid.value })
    }

    @Test
    fun `searchAssets escapes LIKE wildcards`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", path = "/g/100%_done.png"))
        r.saveAsset(entry("b", path = "/g/100abc.png"))
        r.saveAsset(entry("c", path = "/g/back\\slash.png"))
        assertEquals(listOf("a"), r.searchAssets("100%_", 10, 0).first().getOrNull()!!.map { it.uuid.value })
        assertEquals(listOf("c"), r.searchAssets("\\", 10, 0).first().getOrNull()!!.map { it.uuid.value })
        assertEquals(2, r.searchAssets("100", 10, 0).first().getOrNull()!!.size)
    }

    @Test
    fun `unprocessed lifecycle - markMlProcessed and markMlFailed`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", importedAt = 1))
        r.saveAsset(entry("b", importedAt = 2))
        r.saveAsset(entry("c", importedAt = 3))
        assertEquals(3L, r.countUnprocessedAssets().getOrNull())
        assertEquals(listOf("a", "b", "c"),
            r.getUnprocessedAssets(10, 0).first().getOrNull()!!.map { it.uuid.value })

        r.markMlProcessed(AssetUuid("a"), 500L)
        r.markMlFailed(AssetUuid("b"), 600L)

        assertEquals(1L, r.countUnprocessedAssets().getOrNull())
        assertEquals(listOf("c"), r.getUnprocessedAssets(10, 0).first().getOrNull()!!.map { it.uuid.value })
        val a = r.get("a")!!
        assertTrue(a.mlProcessed); assertFalse(a.mlFailed); assertEquals(500L, a.mlAttemptedAt)
        val b = r.get("b")!!
        assertTrue(b.mlFailed); assertFalse(b.mlProcessed); assertEquals(600L, b.mlAttemptedAt)
    }

    @Test
    fun `field updaters persist`() = runBlocking<Unit> {
        val r = repo()
        val id = AssetUuid("a")
        r.saveAsset(entry("a"))

        r.updateFilePath(id, "/new/path.png", "../new/path.png")
        r.updateTags(id, listOf("t1", "t2"))
        r.updateAutoLabels(id, listOf("cat"), "LOCAL")
        r.updateOcrText(id, "hello")
        r.updateCloudDescription(id, "a cat", "CLOUD")
        r.updatePageUuids(id, listOf("p1", "p2"))

        val got = r.get("a")!!
        assertEquals("/new/path.png", got.filePath)
        assertEquals("../new/path.png", got.relativePath)
        assertEquals(listOf("t1", "t2"), got.tags)
        assertEquals(listOf("cat"), got.autoLabels)
        assertEquals("hello", got.ocrText)
        assertEquals("a cat", got.cloudDescription)
        assertEquals("CLOUD", got.mlTagsSource)
        assertEquals(listOf("p1", "p2"), got.pageUuids)

        r.updateOcrText(id, null)
        r.updateCloudDescription(id, null, "NONE")
        assertNull(r.get("a")!!.ocrText)
        assertNull(r.get("a")!!.cloudDescription)
    }

    @Test
    fun `deleteAsset removes the row`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a"))
        assertTrue(r.deleteAsset(AssetUuid("a")).isRight())
        assertNull(r.get("a"))
        assertEquals(0L, r.countAssets().getOrNull())
    }

    @Test
    fun `getAssetPage BY_DATE_ADDED walks keyset cursor`() = runBlocking<Unit> {
        val r = repo()
        repeat(5) { r.saveAsset(entry("u$it", importedAt = it.toLong())) }
        val p1 = r.getAssetPage(null, "", AssetSortOrder.BY_DATE_ADDED, null, null, null, null, 2)
            .first().getOrNull()!!
        assertEquals(listOf("u4", "u3"), p1.map { it.uuid.value })
        val last = p1.last()
        val p2 = r.getAssetPage(null, "", AssetSortOrder.BY_DATE_ADDED, last.importedAtMs, null, null, last.uuid.value, 2)
            .first().getOrNull()!!
        assertEquals(listOf("u2", "u1"), p2.map { it.uuid.value })
    }

    @Test
    fun `getAssetPage BY_NAME and BY_SIZE order and cursor`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", path = "/b.png", size = 10))
        r.saveAsset(entry("b", path = "/a.png", size = 30))
        r.saveAsset(entry("c", path = "/c.png", size = 20))

        val byName = r.getAssetPage(null, "", AssetSortOrder.BY_NAME, null, null, null, null, 10)
            .first().getOrNull()!!
        assertEquals(listOf("b", "a", "c"), byName.map { it.uuid.value })
        val nameNext = r.getAssetPage(null, "", AssetSortOrder.BY_NAME, null, "/a.png", null, "b", 10)
            .first().getOrNull()!!
        assertEquals(listOf("a", "c"), nameNext.map { it.uuid.value })

        val bySize = r.getAssetPage(null, "", AssetSortOrder.BY_SIZE, null, null, null, null, 10)
            .first().getOrNull()!!
        assertEquals(listOf("b", "c", "a"), bySize.map { it.uuid.value })
        val sizeNext = r.getAssetPage(null, "", AssetSortOrder.BY_SIZE, null, null, 30L, "b", 10)
            .first().getOrNull()!!
        assertEquals(listOf("c", "a"), sizeNext.map { it.uuid.value })
    }

    @Test
    fun `getAssetPage with mediaType filter covers every sort order`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("i1", AssetMediaType.IMAGE, path = "/i1", size = 5, importedAt = 1))
        r.saveAsset(entry("i2", AssetMediaType.IMAGE, path = "/i2", size = 6, importedAt = 2))
        r.saveAsset(entry("p1", AssetMediaType.PDF, path = "/p1", size = 7, importedAt = 3))
        for (order in AssetSortOrder.entries) {
            val page = r.getAssetPage(AssetMediaType.IMAGE, "", order, null, null, null, null, 10)
                .first().getOrNull()!!
            assertEquals(setOf("i1", "i2"), page.map { it.uuid.value }.toSet(), "order=$order")
        }
    }

    @Test
    fun `getAssetPage with search query escapes wildcards`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", path = "/g/50%_off.png"))
        r.saveAsset(entry("b", path = "/g/50xyz.png"))
        val hits = r.getAssetPage(null, "50%_", AssetSortOrder.BY_DATE_ADDED, null, null, null, null, 10)
            .first().getOrNull()!!
        assertEquals(listOf("a"), hits.map { it.uuid.value })
    }

    @Test
    fun `orphan listing and count`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("o1", importedAt = 1))
        r.saveAsset(entry("o2", importedAt = 2))
        r.saveAsset(entry("n", importedAt = 3))
        markOrphan("o1"); markOrphan("o2")
        assertEquals(2L, r.countOrphanedAssets().getOrNull())
        val all = r.getOrphanedAssets(null, null, 10).first().getOrNull()!!
        assertEquals(listOf("o2", "o1"), all.map { it.uuid.value })
        val next = r.getOrphanedAssets(2L, "o2", 10).first().getOrNull()!!
        assertEquals(listOf("o1"), next.map { it.uuid.value })
    }

    @Test
    fun `getDistinctTags dedups and sorts, ignoring empty tag lists`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", tags = listOf("zeta", "alpha")))
        r.saveAsset(entry("b", tags = listOf("alpha", "beta")))
        r.saveAsset(entry("c", tags = emptyList()))
        assertEquals(listOf("alpha", "beta", "zeta"), r.getDistinctTags().getOrNull())
    }

    @Test
    fun `saveAsset with an existing uuid is ignored, not replaced`() = runBlocking<Unit> {
        val r = repo()
        r.saveAsset(entry("a", size = 1))
        r.saveAsset(entry("a", size = 2))
        assertEquals(1L, r.countAssets().getOrNull())
        assertNotNull(r.get("a"))
        assertEquals(1L, r.get("a")!!.sizeBytes)
    }
}
