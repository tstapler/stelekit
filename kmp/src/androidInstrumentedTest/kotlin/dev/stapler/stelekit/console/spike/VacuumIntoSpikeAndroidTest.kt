// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stapler.stelekit.console.spike.AndroidSpikeSupport.record
import dev.stapler.stelekit.db.DriverFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Spike 0.3b, Android half (dev plan 0.2b): does the requery SQLite (3.49.0) and the platform SQLite support
 * `VACUUM INTO`, and does the live-DB swap order work on the real Android driver? The graph-sized (451 MB)
 * timing/heap run is JVM-side (`DbSwapSpikeTest`); the concurrent-write run on a 450 MB Android DB is Task 3.3f.
 */
@RunWith(AndroidJUnit4::class)
class VacuumIntoSpikeAndroidTest {

    private val spike = "0.3b-android"

    @Test
    fun spike0_3b_should_RecordVacuumIntoSupportAndIntegrity_When_RunOnRequeryAndPlatformSqlite() {
        val dir = AndroidSpikeSupport.freshDir("vacuum")
        val db = dir.resolve("app.db")
        val app = AndroidSpikeSupport.appDriver(db)
        try {
            AndroidSpikeSupport.seed(db, pages = 300)
            val rw = AndroidSpikeSupport.requeryReadWrite(db)
            val pages = AndroidSpikeSupport.scalarLong(rw, "SELECT COUNT(*) FROM pages")
            val copy = dir.resolve("b.db")
            val (result, ms) = timedMs { runCatching { rw.execSQL("VACUUM INTO '${copy.absolutePath}'") } }
            rw.close()
            if (result.isSuccess) {
                val check = AndroidSpikeSupport.requeryReadOnly(copy)
                record(spike, "requery_vacuum_into", "ms=$ms live_bytes=${db.length()} copy_bytes=${copy.length()} integrity=${AndroidSpikeSupport.scalarString(check, "PRAGMA integrity_check")} copy_pages=${AndroidSpikeSupport.scalarLong(check, "SELECT COUNT(*) FROM pages")} live_pages=$pages")
                check.close()
            } else {
                record(spike, "requery_vacuum_into", "FAILS: ${result.exceptionOrNull()?.javaClass?.simpleName}: ${result.exceptionOrNull()?.message}")
            }

            // From the read-only console handle (no read-write handle needed?).
            val roCopy = dir.resolve("b-ro.db")
            val ro = AndroidSpikeSupport.requeryReadOnly(db)
            val roResult = runCatching { ro.execSQL("VACUUM INTO '${roCopy.absolutePath}'") }
            ro.close()
            record(spike, "requery_vacuum_into_from_readonly_handle", if (roResult.isSuccess) "works (copy_bytes=${roCopy.length()})" else "FAILS: ${roResult.exceptionOrNull()?.message}")

            // Platform SQLite (system library, version depends on API level).
            val pfCopy = dir.resolve("b-pf.db")
            val pf = android.database.sqlite.SQLiteDatabase.openDatabase(db.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            val pfVersion = pf.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
            val pfResult = runCatching { pf.execSQL("VACUUM INTO '${pfCopy.absolutePath}'") }
            pf.close()
            record(spike, "platform_vacuum_into", "sqlite=$pfVersion " + if (pfResult.isSuccess) "works (copy_bytes=${pfCopy.length()})" else "FAILS: ${pfResult.exceptionOrNull()?.message}")
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3b_should_RestoreSnapshotAndRollBack_When_LiveDbSwapOrderRunsOnAndroidDriver() {
        val dir = AndroidSpikeSupport.freshDir("swap")
        val live = dir.resolve("graph.db")
        val snapshot = dir.resolve("snapshot.db")
        var app = AndroidSpikeSupport.appDriver(live)
        try {
            AndroidSpikeSupport.seed(live, pages = 50, blocksPerPage = 2, contentBytes = 100)
            val console = AndroidSpikeSupport.requeryReadOnly(live)
            val snapshotPages = AndroidSpikeSupport.scalarLong(console, "SELECT COUNT(*) FROM pages")
            AndroidSpikeSupport.requeryReadWrite(live).let { w ->
                try { w.execSQL("VACUUM INTO '${snapshot.absolutePath}'") } finally { w.close() }
            }
            repeat(40) { AndroidSpikeSupport.appInsertPage(app, "v2-$it") }
            val v2Pages = AndroidSpikeSupport.scalarLong(console, "SELECT COUNT(*) FROM pages")

            record(spike, "files_before_close", dir.list()?.sorted())
            console.close()
            app.close()
            record(spike, "files_after_close", dir.list()?.sorted())
            val sidecars = listOf("-wal", "-shm").associateWith { File(live.path + it).exists() }
            record(spike, "live_path_exists_before_rename", "${live.path} exists=${live.exists()} length=${live.length()}")
            val renamed = mutableListOf<String>()
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = File(live.path + suffix)
                if (!f.exists()) continue
                Files.move(f.toPath(), File(f.path + ".pre-restore").toPath())
                renamed += suffix.ifEmpty { "db" }
            }
            Files.copy(snapshot.toPath(), live.toPath())
            listOf("-wal", "-shm").forEach { File(live.path + it).delete() }
            app = AndroidSpikeSupport.appDriver(live)
            val verify = AndroidSpikeSupport.requeryReadOnly(live)
            val integrity = AndroidSpikeSupport.scalarString(verify, "PRAGMA integrity_check")
            val restored = AndroidSpikeSupport.scalarLong(verify, "SELECT COUNT(*) FROM pages")
            verify.close()
            AndroidSpikeSupport.appInsertPage(app, "post-restore")
            record(spike, "swap_order", "sidecars_after_close=$sidecars renamed=$renamed integrity=$integrity restored_pages=$restored snapshot_pages=$snapshotPages v2_pages=$v2Pages")
            assertEquals("ok", integrity)
            assertEquals(snapshotPages, restored)
            app.close()

            listOf("", "-wal", "-shm").forEach { File(live.path + it).delete() }
            listOf("", "-wal", "-shm").forEach { s ->
                val f = File(live.path + s + ".pre-restore")
                if (f.exists()) Files.move(f.toPath(), File(live.path + s).toPath())
            }
            app = AndroidSpikeSupport.appDriver(live)
            val back = AndroidSpikeSupport.requeryReadOnly(live)
            val rolledBack = AndroidSpikeSupport.scalarLong(back, "SELECT COUNT(*) FROM pages")
            back.close()
            record(spike, "swap_rollback", "pages_after_rollback=$rolledBack expected=$v2Pages")
            assertEquals(v2Pages, rolledBack)
        } finally {
            runCatching { app.close() }; dir.deleteRecursively()
        }
    }
}
