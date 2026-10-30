// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Shared fixtures for the Story 0.3 capability spikes. Results are printed as `SPIKE-RESULT` lines. */
internal object SpikeSupport {

    /** `/tmp` can be a small tmpfs; `SPIKE_TMP` points the large-DB runs at a real disk. */
    fun tempDir(prefix: String): File {
        val root = System.getenv("SPIKE_TMP")?.let(::File)?.also { it.mkdirs() }
        return (if (root != null) Files.createTempDirectory(root.toPath(), prefix) else Files.createTempDirectory(prefix)).toFile()
    }

    /** 451 MB-class runs set `SPIKE_DB_BYTES=472907776`; the default keeps the suite fast. */
    fun targetDbBytes(): Long = System.getenv("SPIKE_DB_BYTES")?.toLongOrNull() ?: (8L * 1024 * 1024)

    fun record(spike: String, key: String, value: Any?) = println("SPIKE-RESULT $spike $key=$value")

    fun url(file: File) = "jdbc:sqlite:${file.absolutePath}"

    // sqlite-jdbc is only a runtime dependency of the test classpath (Gradle and Bazel), so `org.sqlite.SQLiteConfig`
    // is reached by reflection. `setReadOnly(true)` is the exact API the plan specifies for the console handle.
    private fun configuredConnection(file: File, readOnly: Boolean): Connection {
        val cfg = Class.forName("org.sqlite.SQLiteConfig").getDeclaredConstructor().newInstance()
        if (readOnly) cfg.javaClass.getMethod("setReadOnly", Boolean::class.javaPrimitiveType).invoke(cfg, true)
        return cfg.javaClass.getMethod("createConnection", String::class.java).invoke(cfg, url(file)) as Connection
    }

    fun readOnlyConnection(file: File): Connection = configuredConnection(file, readOnly = true)

    fun readWriteConnection(file: File): Connection =
        configuredConnection(file, readOnly = false).also { it.createStatement().use { s -> s.execute("PRAGMA busy_timeout=10000") } }

    fun scalarLong(conn: Connection, sql: String): Long =
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    fun scalarString(conn: Connection, sql: String): String =
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }

    /**
     * Inserts pages with ~[blocksPerPage] blocks of [contentBytes] each over a plain JDBC connection until the
     * main file plus WAL reaches [targetBytes]. Triggers (FTS) fire, so the size reflects a real graph DB.
     */
    fun seed(file: File, targetBytes: Long, blocksPerPage: Int = 12, contentBytes: Int = 1500): Int {
        var pages = 0
        readWriteConnection(file).use { c ->
            c.autoCommit = false
            val insPage = c.prepareStatement(
                "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES (?,?,?,?)",
            )
            val insBlock = c.prepareStatement(
                "INSERT INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES (?,?,?,?,?,?)",
            )
            val rnd = java.util.Random(42)
            val words = Array(512) { "w" + Integer.toString(rnd.nextInt(1 shl 24), 36) }
            fun size() = file.length() + File(file.path + "-wal").let { if (it.exists()) it.length() else 0L }
            while (pages == 0 || size() < targetBytes) {
                repeat(200) {
                    val pu = "page-$pages"
                    insPage.setString(1, pu); insPage.setString(2, "Page $pages"); insPage.setLong(3, 1L); insPage.setLong(4, 1L)
                    insPage.addBatch()
                    pages++
                    for (b in 0 until blocksPerPage) {
                        val sb = StringBuilder(contentBytes + 16)
                        while (sb.length < contentBytes) sb.append(words[rnd.nextInt(words.size)]).append(' ')
                        insBlock.setString(1, "$pu-b$b"); insBlock.setString(2, pu); insBlock.setString(3, sb.toString())
                        insBlock.setString(4, "a$b"); insBlock.setLong(5, 1L); insBlock.setLong(6, 1L)
                        insBlock.addBatch()
                    }
                    if (pages % 200 == 0) {
                        insPage.executeBatch(); insBlock.executeBatch(); c.commit()
                    }
                }
                insPage.executeBatch(); insBlock.executeBatch(); c.commit()
                c.createStatement().use { it.execute("PRAGMA wal_checkpoint(PASSIVE)") }
            }
            c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
        return pages
    }

    /** Samples used heap on a daemon thread; [peakBytes] is the maximum seen between start and [stop]. */
    class HeapSampler : AutoCloseable {
        private val peak = AtomicLong(0)

        @Volatile private var running = true
        private val t = thread(isDaemon = true, name = "heap-sampler") {
            val rt = Runtime.getRuntime()
            while (running) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }
                Thread.sleep(25)
            }
        }

        fun peakBytes(): Long = peak.get()
        override fun close() { running = false; t.join(1000) }
    }

    inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    const val RECURSIVE_CTE = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<1000000000) SELECT count(*) FROM r"
}
