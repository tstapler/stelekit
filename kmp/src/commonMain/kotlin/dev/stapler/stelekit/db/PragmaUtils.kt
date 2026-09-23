package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

/** Runs PRAGMA optimize on [driver] synchronously, then closes it. No-op on platforms without a persistent DB. */
expect fun pragmaOptimizeAndClose(driver: SqlDriver?)
