// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import dev.stapler.stelekit.db.DriverFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spikes 0.3b (dev plan 0.2b): `VACUUM INTO` integrity/time/heap on a graph-sized DB, and the live-DB swap order from
 * dev ADR-002 item 4 on a temp graph. DB size comes from `SPIKE_DB_BYTES` (default 8 MB; the recorded run uses
 * 472907776, i.e. 451 MB). The Android half is `VacuumIntoSpikeAndroidTest`.
 */
class DbSwapSpikeTest {

    private val spike = "0.3b"
    private val countPages = "SELECT COUNT(*) FROM pages"
    private val countBlocks = "SELECT COUNT(*) FROM blocks"

    private fun appInsert(driver: app.cash.sqldelight.db.SqlDriver, id: String) {
        driver.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$id','$id',1,1)", 0).value
    }

    @Test
    fun spike0_3b_should_RecordVacuumIntoIntegrityTimeAndPeakHeap_When_GraphSizedDbIsBackedUp() {
        val dir = SpikeSupport.tempDir("spike03b")
        try {
            val db = dir.resolve("app.db")
            val app = DriverFactory().createDriver(SpikeSupport.url(db))
            val (pages, seedMs) = SpikeSupport.timed { SpikeSupport.seed(db, SpikeSupport.targetDbBytes()) }
            val liveBytes = db.length()
            SpikeSupport.record(spike, "seeded", "pages=$pages bytes=$liveBytes seed_ms=$seedMs")

            val copy = dir.resolve("b.db")
            val writeLatencyMaxMs = AtomicLong()
            val writeErrors = AtomicInteger()
            val writes = AtomicInteger()
            val stop = AtomicBoolean(false)
            val writer = thread(name = "app-writer") {
                var i = 0
                while (!stop.get()) {
                    val t0 = System.nanoTime()
                    try { appInsert(app, "during-vacuum-${i++}"); writes.incrementAndGet() } catch (e: Exception) { writeErrors.incrementAndGet(); SpikeSupport.record(spike, "write_error", e.message) }
                    writeLatencyMaxMs.accumulateAndGet((System.nanoTime() - t0) / 1_000_000) { a, b -> maxOf(a, b) }
                    Thread.sleep(5)
                }
            }

            val rt = Runtime.getRuntime()
            System.gc()
            val baselineHeap = rt.totalMemory() - rt.freeMemory()
            val backupConn = SpikeSupport.readWriteConnection(db)
            val elapsed = SpikeSupport.HeapSampler().use { sampler ->
                val (_, ms) = SpikeSupport.timed { backupConn.createStatement().use { it.execute("VACUUM INTO '${copy.absolutePath}'") } }
                SpikeSupport.record(spike, "vacuum_into_peak_heap_mb", (sampler.peakBytes()) / MB)
                ms
            }
            stop.set(true); writer.join(10_000); backupConn.close()

            val check = SpikeSupport.readOnlyConnection(copy)
            val integrity = SpikeSupport.scalarString(check, "PRAGMA integrity_check")
            val copyPages = SpikeSupport.scalarLong(check, countPages)
            val copyBlocks = SpikeSupport.scalarLong(check, countBlocks)
            check.close()
            SpikeSupport.record(spike, "vacuum_into", "ms=$elapsed live_bytes=$liveBytes copy_bytes=${copy.length()} integrity_check=$integrity copy_pages=$copyPages copy_blocks=$copyBlocks baseline_heap_mb=${baselineHeap / MB}")
            SpikeSupport.record(spike, "concurrent_app_writes_during_vacuum_into", "ok=${writes.get()} errors=${writeErrors.get()} max_write_latency_ms=${writeLatencyMaxMs.get()}")
            assertEquals("ok", integrity)
            assertTrue(copyPages >= pages.toLong(), "copy lost pages: $copyPages < $pages")
            assertEquals(0, writeErrors.get(), "app writes failed while VACUUM INTO ran (database is locked?)")

            // VACUUM INTO from the read-only console handle: does the backup need a read-write handle?
            val roCopy = dir.resolve("b-ro.db")
            val ro = SpikeSupport.readOnlyConnection(db)
            val roResult = runCatching { ro.createStatement().use { it.execute("VACUUM INTO '${roCopy.absolutePath}'") } }
            ro.close()
            SpikeSupport.record(spike, "vacuum_into_from_readonly_handle", if (roResult.isSuccess) "works (copy_bytes=${roCopy.length()})" else "fails: ${roResult.exceptionOrNull()?.message}")
            app.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3b_should_RestoreSnapshotAndRollBack_When_LiveDbSwapOrderIsFollowedOnTempGraph() {
        val dir = SpikeSupport.tempDir("spike03b-swap")
        try {
            val live = dir.resolve("graph.db")
            val snapshot = dir.resolve("snapshot.db")
            var app = DriverFactory().createDriver(SpikeSupport.url(live))
            SpikeSupport.seed(live, 1L, blocksPerPage = 2, contentBytes = 100)
            val console = SpikeSupport.readOnlyConnection(live)
            val snapshotPages = SpikeSupport.scalarLong(console, countPages)

            // Snapshot (v1), then later app writes (v2) that the restore will discard.
            SpikeSupport.readWriteConnection(live).use { c -> c.createStatement().use { it.execute("VACUUM INTO '${snapshot.absolutePath}'") } }
            repeat(50) { appInsert(app, "v2-$it") }
            val livePagesV2 = SpikeSupport.scalarLong(console, countPages)
            assertEquals(snapshotPages + 50, livePagesV2)
            val staleWal = dir.resolve("stale.wal")
            val walFile = File(live.path + "-wal")
            val walExistedWhileOpen = walFile.exists() && walFile.length() > 0
            if (walExistedWhileOpen) Files.copy(walFile.toPath(), staleWal.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // 0. Validate the snapshot on a temp copy before touching the live DB.
            val validate = SpikeSupport.readOnlyConnection(snapshot)
            assertEquals("ok", SpikeSupport.scalarString(validate, "PRAGMA integrity_check"))
            validate.close()

            // 1. Close the console reader. 2. Drain + close the app driver (no actor in this spike; closing is the drain).
            console.close()
            app.close()
            val sidecarsAfterClose = listOf("-wal", "-shm").associateWith { File(live.path + it).exists() }

            // 3. Rename live (+ sidecars) to *.pre-restore.
            val renamed = mutableListOf<String>()
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = File(live.path + suffix)
                if (f.exists()) { Files.move(f.toPath(), File(f.path + ".pre-restore").toPath()); renamed += suffix.ifEmpty { "db" } }
            }
            // 4. Copy the snapshot in; 5. delete any stale -wal/-shm; 6. reopen through the app's own factory.
            Files.copy(snapshot.toPath(), live.toPath())
            for (suffix in listOf("-wal", "-shm")) File(live.path + suffix).delete()
            app = DriverFactory().createDriver(SpikeSupport.url(live))
            val verify = SpikeSupport.readOnlyConnection(live)
            val integrity = SpikeSupport.scalarString(verify, "PRAGMA integrity_check")
            val restoredPages = SpikeSupport.scalarLong(verify, countPages)
            verify.close()
            SpikeSupport.record(spike, "swap_order", "walExistedWhileOpen=$walExistedWhileOpen sidecars_after_close=$sidecarsAfterClose renamed=$renamed integrity=$integrity restored_pages=$restoredPages snapshot_pages=$snapshotPages")
            assertEquals("ok", integrity)
            assertEquals(snapshotPages, restoredPages)
            appInsert(app, "post-restore-write") // the reopened pool is writable
            app.close()

            // Rollback path: put the *.pre-restore files back and confirm v2 data returns.
            for (suffix in listOf("", "-wal", "-shm")) File(live.path + suffix).delete()
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = File(live.path + suffix + ".pre-restore")
                if (f.exists()) Files.move(f.toPath(), File(live.path + suffix).toPath())
            }
            val back = SpikeSupport.readOnlyConnection(live)
            val rolledBack = SpikeSupport.scalarLong(back, countPages)
            back.close()
            SpikeSupport.record(spike, "swap_rollback", "pages_after_rollback=$rolledBack expected=$livePagesV2")
            assertEquals(livePagesV2, rolledBack)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3b_should_RecordWhatHappens_When_AStaleWalIsLeftNextToTheRestoredDb() {
        val dir = SpikeSupport.tempDir("spike03b-stale")
        try {
            val live = dir.resolve("graph.db")
            val snapshot = dir.resolve("snapshot.db")
            val app = DriverFactory().createDriver(SpikeSupport.url(live))
            SpikeSupport.seed(live, 1L, blocksPerPage = 2, contentBytes = 100)
            SpikeSupport.readWriteConnection(live).use { c -> c.createStatement().use { it.execute("VACUUM INTO '${snapshot.absolutePath}'") } }
            val snapshotPages = SpikeSupport.readOnlyConnection(snapshot).use { SpikeSupport.scalarLong(it, countPages) }
            repeat(50) { appInsert(app, "v2-$it") }
            val wal = File(live.path + "-wal")
            if (!wal.exists() || wal.length() == 0L) {
                SpikeSupport.record(spike, "stale_wal", "SKIPPED: no non-empty -wal while the app driver was open")
                app.close(); return
            }
            val walCopy = dir.resolve("wal.copy").also { Files.copy(wal.toPath(), it.toPath()) }
            app.close()
            // Restore WITHOUT deleting the stale sidecar: replant the old WAL beside the snapshot-as-live.
            val restored = dir.resolve("restored.db")
            Files.copy(snapshot.toPath(), restored.toPath())
            Files.copy(walCopy.toPath(), File(restored.path + "-wal").toPath())
            val outcome = runCatching {
                SpikeSupport.readOnlyConnection(restored).use { c ->
                    "integrity=${SpikeSupport.scalarString(c, "PRAGMA integrity_check")} pages=${SpikeSupport.scalarLong(c, countPages)} snapshot_pages=$snapshotPages"
                }
            }
            SpikeSupport.record(spike, "stale_wal_left_in_place", outcome.getOrElse { "open failed: ${it.message}" })
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
