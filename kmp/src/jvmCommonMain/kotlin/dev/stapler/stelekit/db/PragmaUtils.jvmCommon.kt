package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import java.util.logging.Level
import java.util.logging.Logger

private val log = Logger.getLogger("PragmaUtils")

actual fun pragmaOptimizeAndClose(driver: SqlDriver?) {
    try { runBlocking { driver?.execute(null, "PRAGMA optimize", 0)?.await() } } catch (_: Exception) {}
    // wal_autocheckpoint (see DriverFactory) triggers passive checkpoints during the session, but
    // passive checkpoints never shrink the .db-wal file on disk — they just reuse it from the
    // start once fully flushed. Without an explicit TRUNCATE checkpoint the WAL file's on-disk
    // size only ever grows to its historical peak (observed: 5GB WAL for a 722MB db), and every
    // subsequent read/write has to wade through it. Truncate on every graph close so the file
    // stays proportional to actual write volume between sessions.
    try {
        runBlocking { driver?.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)?.await() }
    } catch (e: Exception) {
        log.log(Level.WARNING, "wal_checkpoint(TRUNCATE) failed — WAL file may not shrink", e)
    }
    try { driver?.close() } catch (_: Exception) {}
}
