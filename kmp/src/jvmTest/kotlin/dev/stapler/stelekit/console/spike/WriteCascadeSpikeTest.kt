// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Spike 0.3c (dev plan 0.2c): does a raw `UPDATE blocks` through `DatabaseWriteActor.execute` ever make a
 * `GraphWriter` path write markdown? Real SQLDelight repositories on a file DB, a real `GraphWriter` with auto-save
 * running (500 ms debounce) on a real graph directory, and a passive subscriber on `blockInvalidations`.
 *
 * Scope limit: the only production subscriber of `blockInvalidations` is `BlockStateManager.observePage`
 * (read-only re-pull); it is not constructed here. The raw write goes over a separate JDBC connection inside
 * the actor lambda, which is how a console `sql!` would reach the DB.
 */
class WriteCascadeSpikeTest {

    private val spike = "0.3c"

    private fun snapshot(root: File): Map<String, Pair<Long, Long>> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (it.lastModified() to it.length()) }

    @Test
    fun spike0_3c_should_RecordNoMarkdownWrite_When_RawBlockUpdateRunsThroughActorExecute() {
        val dir = SpikeSupport.tempDir("spike03c")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val graphDir = dir.resolve("graph").also { it.mkdirs() }
            val db = dir.resolve("db/app.db")
            val factory = RepositoryFactoryImpl(DriverFactory(), SpikeSupport.url(db))
            val repos = runBlocking { factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope) }
            val actor = checkNotNull(repos.writeActor)

            val now = Clock.System.now()
            val pageUuid = PageUuid("00000000-0000-0000-0000-0000000000c1")
            val blockUuid = BlockUuid("00000000-0000-0000-0000-0000000000c2")
            val page = Page(uuid = pageUuid, name = "CascadeSpike", createdAt = now, updatedAt = now)
            val block = Block(uuid = blockUuid, pageUuid = pageUuid, content = "original", position = "a0", createdAt = now, updatedAt = now)
            runBlocking {
                actor.savePage(page)
                actor.saveBlocks(listOf(block))
            }

            val writer = GraphWriter(PlatformFileSystem.withRoot(graphDir.absolutePath), actor).also {
                it.currentEpoch = GraphEpoch(GraphId("cascade-spike"), EffectiveNotesPath(graphDir.absolutePath), 1L)
            }
            writer.startAutoSave(scope)
            runBlocking { writer.savePage(page, listOf(block), EffectiveNotesPath(graphDir.absolutePath)) }
            val md = graphDir.resolve("pages/CascadeSpike.md")
            assertTrue(md.exists(), "setup: sanctioned GraphWriter.savePage should have written $md")
            val before = snapshot(graphDir)
            val contentBefore = md.readText()

            val invalidations = AtomicInteger()
            scope.launch { actor.blockInvalidations.collect { invalidations.incrementAndGet() } }
            runBlocking { delay(200) }

            val raw = runBlocking {
                actor.execute {
                    SpikeSupport.readWriteConnection(db).use { c ->
                        c.createStatement().use { it.executeUpdate("UPDATE blocks SET content='zz' WHERE uuid='${blockUuid.value}'") }
                    }
                    Unit.right()
                }
            }
            assertTrue(raw.isRight(), "raw write failed: $raw")
            runBlocking { delay(DEBOUNCE_WINDOW_MS) }

            val after = snapshot(graphDir)
            val ro = SpikeSupport.readOnlyConnection(db)
            val dbContent = SpikeSupport.scalarString(ro, "SELECT content FROM blocks WHERE uuid='${blockUuid.value}'")
            val ftsHits = SpikeSupport.scalarLong(ro, "SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'zz'")
            ro.close()
            SpikeSupport.record(spike, "raw_update_through_actor", "db_content=$dbContent fts_hits_for_zz=$ftsHits invalidations_seen=${invalidations.get()}")
            SpikeSupport.record(spike, "markdown_after_window", "files_before=${before.keys} files_after=${after.keys} unchanged=${before == after} content_has_zz=${md.readText().contains("zz")}")
            assertEquals("zz", dbContent)
            assertTrue(invalidations.get() >= 1, "actor.execute should emit an invalidation")
            assertEquals(before, after, "a raw actor write changed the graph directory")
            assertEquals(contentBefore, md.readText())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** 500 ms GraphWriter debounce x 5. */
        const val DEBOUNCE_WINDOW_MS = 2500L
    }
}
