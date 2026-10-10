package dev.stapler.stelekit.db.sqlite

import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

/**
 * Default [SqliteDriverProvider] backed by the Requery bundled SQLite binary (3.49+).
 *
 * The bundled library is compiled with FTS5, FTS4, FTS3, JSON1, RTREE, and WAL enabled,
 * so [capabilities] is always [FULL_CAPABILITIES] regardless of the Android API level or
 * OEM SQLite version on the device.
 *
 * This is the production default — no configuration required. It is used whenever
 * [dev.stapler.stelekit.db.DriverFactory.Companion.driverProvider] is null.
 */
class RequeryDriverProvider : SqliteDriverProvider {
    override val factory = RequerySQLiteOpenHelperFactory()
    override val capabilities = FULL_CAPABILITIES
}
