package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

@JsName("Worker")
external fun createWorker(url: dynamic): Worker

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {
        // No-op on JS
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        val dbName = jdbcUrl.substringAfter("jdbc:sqlite:")
        val url = js("new URL(\"@cashapp/sqldelight-sqljs-worker/sqljs.worker.js\", import.meta.url)")
        val worker = createWorker(url)
        return WebWorkerDriver(worker)
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"