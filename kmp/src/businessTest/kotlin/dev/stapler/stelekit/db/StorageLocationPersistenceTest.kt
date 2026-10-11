package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Round-trip persistence for the `storage_locations` table (Task 1.1.3a/e).
 * Uses an in-memory JDBC driver so MigrationRunner.applyAll runs automatically inside
 * DriverFactory.createDriver — no real files touched.
 */
class StorageLocationPersistenceTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: SteleDatabase
    private lateinit var restricted: RestrictedDatabaseQueries

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    @Test
    fun upsertStorageLocation_should_RoundTripAppOwnedRow_When_ReadBack() = runTest {
        @OptIn(DirectSqlWrite::class)
        restricted.upsertStorageLocation(
            graph_id = "g1",
            kind = "AppOwned",
            tree_uri = null,
            real_path = null,
            display_name = null,
            updated_at_epoch_ms = 1_000L,
        )

        val row = database.steleDatabaseQueries.selectStorageLocation("g1").executeAsOneOrNull()

        assertEquals("g1", row?.graph_id)
        assertEquals("AppOwned", row?.kind)
        assertNull(row?.tree_uri)
    }

    @Test
    fun upsertStorageLocation_should_RoundTripSafFolderTreeUri_When_ReadBack() = runTest {
        @OptIn(DirectSqlWrite::class)
        restricted.upsertStorageLocation(
            graph_id = "g2",
            kind = "SafFolder",
            tree_uri = "content://com.android.externalstorage/tree/primary",
            real_path = null,
            display_name = null,
            updated_at_epoch_ms = 2_000L,
        )

        val row = database.steleDatabaseQueries.selectStorageLocation("g2").executeAsOneOrNull()

        assertEquals("SafFolder", row?.kind)
        assertEquals("content://com.android.externalstorage/tree/primary", row?.tree_uri)
    }

    // REQ-1 error path (validation.md): absence must read as "not AppOwned," never inferred.
    @Test
    fun selectStorageLocation_should_ReturnNull_When_NoRowExists() = runTest {
        val row = database.steleDatabaseQueries.selectStorageLocation("no-such-graph").executeAsOneOrNull()

        assertNull(row)
    }

    @Test
    fun deleteStorageLocation_should_RemoveRow_When_Called() = runTest {
        @OptIn(DirectSqlWrite::class)
        restricted.upsertStorageLocation(
            graph_id = "g3",
            kind = "AppOwned",
            tree_uri = null,
            real_path = null,
            display_name = null,
            updated_at_epoch_ms = 3_000L,
        )

        @OptIn(DirectSqlWrite::class)
        restricted.deleteStorageLocation("g3")

        val row = database.steleDatabaseQueries.selectStorageLocation("g3").executeAsOneOrNull()
        assertNull(row)
    }
}
