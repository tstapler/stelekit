// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.testing

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.async.coroutines.await
import dev.stapler.stelekit.db.DriverFactory
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive instrumented integration tests for required SQLite capabilities.
 *
 * All raw-connection tests (1–12) open a database via [RequerySQLiteOpenHelperFactory] so they
 * exercise the **bundled** SQLite binary (3.49+, with FTS5/JSON1/WAL), not the system SQLite
 * that may lack FTS5 on AOSP API 30 default images.
 *
 * Tests 13–15 use [DriverFactory] end-to-end to verify the full stack.
 *
 * Run on a connected device or emulator:
 *   ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.testing.SqliteCapabilityTest
 */
@RunWith(AndroidJUnit4::class)
class SqliteCapabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Raw SupportSQLiteDatabase backed by Requery's bundled SQLite (not the system SQLite).
    private lateinit var db: SupportSQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File(context.cacheDir, "sqlite-cap-${System.nanoTime()}.db")
        helper = openRequeryHelper(dbFile)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        runCatching { helper.close() }
        cleanUpDb(dbFile)
    }

    // ── 1. SQLite version ────────────────────────────────────────────────────────

    @Test
    fun sqliteVersion_meetsMinimum_3_49() {
        val version = db.compileStatement("SELECT sqlite_version()").simpleQueryForString() ?: ""
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        assertTrue(
            major > 3 || (major == 3 && minor >= 49),
            "Bundled SQLite $version is below minimum 3.49.0 — bump com.github.requery:sqlite-android"
        )
    }

    // ── 2. WAL mode ──────────────────────────────────────────────────────────────

    @Test
    fun walMode_isActive() {
        val cursor = db.query("PRAGMA journal_mode")
        val mode = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        assertEquals("wal", mode, "Expected WAL journal mode; got '$mode'")
    }

    // ── 3. FTS5 module availability ───────────────────────────────────────────────

    @Test
    fun fts5Module_isAvailable() {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS _probe_fts USING fts5(content)")
        val cursor = db.query(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='_probe_fts'")
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        assertEquals(1, count, "FTS5 module unavailable — 'no such module: fts5' prevented table creation")
    }

    // ── 4. FTS5 full-text search ──────────────────────────────────────────────────

    @Test
    fun fts5Search_matchesCorrectRow_andExcludesOthers() {
        db.execSQL("CREATE TABLE docs (id INTEGER PRIMARY KEY, body TEXT)")
        db.execSQL("CREATE VIRTUAL TABLE docs_fts USING fts5(body, content=docs, content_rowid=id, tokenize='porter unicode61')")
        db.execSQL("INSERT INTO docs VALUES (1, 'kotlin multiplatform mobile')")
        db.execSQL("INSERT INTO docs VALUES (2, 'swift ios development')")
        db.execSQL("INSERT INTO docs_fts(rowid, body) VALUES (1, 'kotlin multiplatform mobile')")
        db.execSQL("INSERT INTO docs_fts(rowid, body) VALUES (2, 'swift ios development')")

        val cursor = db.query("SELECT rowid FROM docs_fts WHERE docs_fts MATCH 'kotlin'")
        val ids = mutableListOf<Int>()
        while (cursor.moveToNext()) ids += cursor.getInt(0)
        cursor.close()

        assertEquals(listOf(1), ids, "FTS5 search for 'kotlin' should match only row 1, got $ids")
    }

    // ── 5. Porter tokenizer stemming ──────────────────────────────────────────────

    @Test
    fun porterTokenizer_stemsForms_forStemSearch() {
        db.execSQL("CREATE VIRTUAL TABLE _porter_test USING fts5(text, tokenize='porter unicode61')")
        db.execSQL("INSERT INTO _porter_test VALUES ('running quickly')")
        // Porter stems 'running' → 'run'; searching 'run' should match 'running'
        val cursor = db.query("SELECT count(*) FROM _porter_test WHERE _porter_test MATCH 'run'")
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        assertEquals(1, count, "Porter tokenizer should stem 'running' so that 'run' matches it")
    }

    // ── 6. FTS5 insert trigger ────────────────────────────────────────────────────

    @Test
    fun fts5InsertTrigger_updatesIndex_onPageInsert() {
        createPagesWithFtsTriggers()
        db.execSQL("INSERT INTO pages(name) VALUES ('Hello World')")

        val cursor = db.query("SELECT rowid FROM pages_fts WHERE pages_fts MATCH 'hello'")
        val found = cursor.moveToFirst()
        cursor.close()
        assertTrue(found, "FTS5 insert trigger (pages_ai) did not update search index")
    }

    // ── 7. FTS5 delete trigger ────────────────────────────────────────────────────

    @Test
    fun fts5DeleteTrigger_removesFromIndex_onPageDelete() {
        createPagesWithFtsTriggers()
        db.execSQL("INSERT INTO pages(name) VALUES ('DeleteMe')")
        db.execSQL("DELETE FROM pages WHERE name = 'DeleteMe'")

        val cursor = db.query("SELECT rowid FROM pages_fts WHERE pages_fts MATCH 'deleteme'")
        val found = cursor.moveToFirst()
        cursor.close()
        assertTrue(!found, "FTS5 delete trigger (pages_ad) did not remove entry from search index")
    }

    // ── 8. FTS5 update trigger ────────────────────────────────────────────────────

    @Test
    fun fts5UpdateTrigger_replacesIndex_onPageRename() {
        createPagesWithFtsTriggers()
        db.execSQL("INSERT INTO pages(name) VALUES ('OldName')")
        db.execSQL("UPDATE pages SET name = 'NewName' WHERE name = 'OldName'")

        val c1 = db.query("SELECT rowid FROM pages_fts WHERE pages_fts MATCH 'oldname'")
        val oldFound = c1.moveToFirst(); c1.close()
        val c2 = db.query("SELECT rowid FROM pages_fts WHERE pages_fts MATCH 'newname'")
        val newFound = c2.moveToFirst(); c2.close()

        assertTrue(!oldFound, "Old name should be removed from FTS5 index after rename (pages_au trigger)")
        assertTrue(newFound, "New name should appear in FTS5 index after rename (pages_au trigger)")
    }

    // ── 9. WITHOUT ROWID ─────────────────────────────────────────────────────────

    @Test
    fun withoutRowid_tableCanBeCreatedAndQueried() {
        db.execSQL("""
            CREATE TABLE without_rowid_test (
                key TEXT NOT NULL,
                val TEXT NOT NULL,
                PRIMARY KEY (key, val)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("INSERT INTO without_rowid_test VALUES ('k1', 'v1')")
        val cursor = db.query("SELECT val FROM without_rowid_test WHERE key = 'k1'")
        val value = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        assertEquals("v1", value, "WITHOUT ROWID table insert/select failed")
    }

    // ── 10. WITH RECURSIVE CTE ───────────────────────────────────────────────────

    @Test
    fun recursiveCte_traversesHierarchy() {
        db.execSQL("CREATE TABLE nodes (id INTEGER PRIMARY KEY, parent_id INTEGER, name TEXT)")
        db.execSQL("INSERT INTO nodes VALUES (1, NULL, 'root')")
        db.execSQL("INSERT INTO nodes VALUES (2, 1, 'child')")
        db.execSQL("INSERT INTO nodes VALUES (3, 2, 'grandchild')")

        val cursor = db.query("""
            WITH RECURSIVE sub(id, depth) AS (
                SELECT id, 0 FROM nodes WHERE parent_id IS NULL
                UNION ALL
                SELECT n.id, s.depth + 1 FROM nodes n JOIN sub s ON n.parent_id = s.id
            )
            SELECT count(*) FROM sub
        """.trimIndent())
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        assertEquals(3, count, "WITH RECURSIVE CTE should traverse root + 2 descendants")
    }

    // ── 11. JSON1 functions ───────────────────────────────────────────────────────

    @Test
    fun json1_jsonExtract_returnsCorrectValue() {
        val cursor = db.query("SELECT json_extract('{\"key\":\"value\",\"num\":42}', '$.key')")
        val result = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        assertEquals("value", result, "json_extract() should be available and return 'value'")
    }

    @Test
    fun json1_jsonEach_iteratesArray() {
        val cursor = db.query("SELECT json_each.value FROM json_each('[1,2,3]') ORDER BY value")
        val values = mutableListOf<Int>()
        while (cursor.moveToNext()) values += cursor.getInt(0)
        cursor.close()
        assertEquals(listOf(1, 2, 3), values, "json_each() should iterate JSON array elements")
    }

    // ── 12. PRAGMA optimize ───────────────────────────────────────────────────────

    @Test
    fun pragmaOptimize_executesWithoutError() {
        // Used by MigrationRunner.applyAll() on every startup via ANDROID_PRAGMAS
        db.execSQL("PRAGMA optimize")
    }

    // ── 13. Full DriverFactory stack on fresh database ────────────────────────────

    @Test
    fun driverFactory_createDriver_succeedsOnFreshDatabase() {
        val testDb = File(context.cacheDir, "driver-fresh-${System.nanoTime()}.db")
        try {
            DriverFactory.setContext(context)
            // createDriver() runs MigrationRunner.applyAll() internally.
            // If any table creation or migration fails (e.g. FTS5 unavailable), this throws.
            val driver = DriverFactory().createDriver("jdbc:sqlite:${testDb.absolutePath}")
            driver.close()

            // Spot-check: schema_migrations has entries (migrations actually ran)
            val verifyHelper = openRequeryHelper(testDb)
            val verifyDb = verifyHelper.writableDatabase
            val cursor = verifyDb.query("SELECT count(*) FROM schema_migrations")
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            verifyHelper.close()

            assertTrue(count > 0, "schema_migrations should have entries after MigrationRunner.applyAll()")
        } finally {
            cleanUpDb(testDb)
        }
    }

    // ── 14. End-to-end: FTS5 search works after full DriverFactory setup ─────────

    @Test
    fun driverFactory_fts5Search_worksAfterFullSetup() {
        val testDb = File(context.cacheDir, "e2e-fts5-${System.nanoTime()}.db")
        try {
            DriverFactory.setContext(context)
            val driver = DriverFactory().createDriver("jdbc:sqlite:${testDb.absolutePath}")
            runBlocking {
                // Insert a page — fires pages_ai FTS5 trigger
                driver.execute(
                    null,
                    "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES ('uuid-fts5', 'KotlinMultiplatform', 0, 0)",
                    0,
                ).await()

                // FTS5 search via porter tokenizer: 'kotlin' matches 'KotlinMultiplatform'
                val found = driver.executeQuery(
                    null,
                    "SELECT count(*) FROM pages_fts WHERE pages_fts MATCH 'kotlin'",
                    { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: 0L)
                    },
                    0,
                ).await()
                assertEquals(1L, found, "FTS5 search for 'kotlin' should find the inserted page")
            }
            driver.close()
        } finally {
            cleanUpDb(testDb)
        }
    }

    // ── 15. Page insert succeeds without stale FTS5 trigger errors ────────────────

    @Test
    fun driverFactory_freshDb_pageInsertSucceeds_noFts5TriggerError() {
        val testDb = File(context.cacheDir, "trigger-test-${System.nanoTime()}.db")
        try {
            DriverFactory.setContext(context)
            val driver = DriverFactory().createDriver("jdbc:sqlite:${testDb.absolutePath}")
            runBlocking {
                // Must NOT throw "no such table: pages_fts" (the pre-fix production crash)
                driver.execute(
                    null,
                    "INSERT INTO pages (uuid, name, created_at, updated_at) VALUES ('uuid-trigger', 'TriggerSafetyTest', 0, 0)",
                    0,
                ).await()

                val count = driver.executeQuery(
                    null,
                    "SELECT count(*) FROM pages WHERE uuid = 'uuid-trigger'",
                    { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: 0L)
                    },
                    0,
                ).await()
                assertEquals(1L, count, "Inserted page must exist in pages table")
            }
            driver.close()
        } finally {
            cleanUpDb(testDb)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Open [file] backed by Requery's bundled SQLite (WAL enabled via onConfigure). */
    private fun openRequeryHelper(file: File): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                }
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return RequerySQLiteOpenHelperFactory().create(config)
    }

    private fun cleanUpDb(file: File) {
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    private fun createPagesWithFtsTriggers() {
        db.execSQL("CREATE TABLE pages (rowid INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)")
        db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts5(name, content=pages, content_rowid=rowid, tokenize='porter unicode61')")
        db.execSQL("CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END")
        db.execSQL("CREATE TRIGGER pages_ad AFTER DELETE ON pages BEGIN INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name); END")
        db.execSQL("CREATE TRIGGER pages_au AFTER UPDATE OF name ON pages BEGIN INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name); INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END")
    }
}
