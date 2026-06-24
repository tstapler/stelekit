package dev.stapler.stelekit.db.sqlite

import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Anticorruption layer between [dev.stapler.stelekit.db.DriverFactory] and the concrete SQLite
 * binary. Implementations bundle a [SupportSQLiteOpenHelper.Factory] (which controls which native
 * SQLite library is used) with a static [SqliteCapabilities] declaration.
 *
 * Inject into [dev.stapler.stelekit.db.DriverFactory.Companion.driverProvider] to switch the
 * SQLite implementation without touching any repository or business-logic code:
 *
 * A `FrameworkDriverProvider` using the system SQLite can be created by adding
 * `androidx.sqlite:sqlite-framework` and implementing this interface with
 * `FrameworkSQLiteOpenHelperFactory()` and [SYSTEM_SQLITE_CAPABILITIES].
 *
 * [dev.stapler.stelekit.db.DriverFactory] defaults to [RequeryDriverProvider] when this is null.
 */
interface SqliteDriverProvider {
    /** Produces the [SupportSQLiteOpenHelper.Factory] for all driver creation calls. */
    val factory: SupportSQLiteOpenHelper.Factory

    /**
     * Declares the SQLite feature set that [factory]'s native binary exposes.
     *
     * Declarations are static (no runtime probing) — validated at CI time by
     * [dev.stapler.stelekit.testing.SqliteCapabilityTest].
     */
    val capabilities: SqliteCapabilities
}
