// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.content.Context
import com.sun.net.httpserver.HttpServer
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.KnownScopes
import dev.stapler.stelekit.jobs.schedule.KnownScopesResult
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.SettingsReadHealth
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskWorkerDeps
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
import dev.stapler.stelekit.platform.Settings
import java.net.InetSocketAddress
import kotlin.random.Random

/** In-memory [Settings]; `failReads` makes every read throw (a cold or corrupt keystore). */
class ModelDownloadMapSettings : Settings {
    val store = mutableMapOf<String, String>()
    var failReads = false
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String {
        if (failReads) throw IllegalStateException("read failed")
        return store[key] ?: defaultValue
    }
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String): Boolean {
        if (failReads) throw IllegalStateException("read failed")
        return store.containsKey(key)
    }
}

internal fun testModelPayload(): ByteArray = Random(11).nextBytes((DepthModelStorage.MIN_MODEL_BYTES + 512 * 1024).toInt())

internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A local HTTP server that serves a model-sized payload (or an error status) at `/m`. */
class ModelDownloadFakeServer {
    val payload = testModelPayload()
    val sha256 = sha256Hex(payload)
    @Volatile var status = 200
    @Volatile var body: ByteArray = payload
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m") { ex ->
            if (status != 200) {
                ex.sendResponseHeaders(status, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}/m"
    fun stop() = server.stop(0)
}

/**
 * Real registry (with the model-download kind) installed into [TaskWorkerRuntime] with deterministic deps, so a
 * `TaskWorker` that WorkManager builds by reflection runs the real [ModelDownloadTask].
 */
class ModelDownloadFixture(
    val context: Context,
    url: String,
    val settings: ModelDownloadMapSettings = ModelDownloadMapSettings(),
    var network: NetworkSnapshot = NetworkSnapshot(online = true, isMetered = false),
    specTransform: (dev.stapler.stelekit.jobs.schedule.TaskSpec) -> dev.stapler.stelekit.jobs.schedule.TaskSpec = { it },
    sha256: String = sha256Hex(testModelPayload()),
) {
    val registry = TaskRegistry().also {
        it.register(ModelDownloadTask.KIND, specTransform(ModelDownloadTask.spec), ModelDownloadTask(context, url, sha256))
    }
    val store = TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
    val scheduler = WorkManagerTaskScheduler(context, registry, store, AppliedConfigLedger(settings))
    val downloader = DepthModelDownloader(context, scheduler, store, NetworkSnapshotProvider { network }, notificationsOff = { false })
    private val health = SettingsReadHealth(context.getSharedPreferences("model_download_test_health", Context.MODE_PRIVATE))

    fun install() {
        TaskWorkerRuntime.install(registry)
        registerModelDownloadTask(TaskRegistry(), context) // only for the foreground factory
        TaskWorkerRuntime.depsOverride = { _ ->
            TaskWorkerDeps(
                registry = registry,
                store = store,
                runLog = SettingsTaskRunLog(settings),
                network = NetworkSnapshotProvider { network },
                knownScopes = KnownScopes { KnownScopesResult.Unknown },
                hooks = { null },
                health = health,
            )
        }
    }

    fun uninstall() {
        TaskWorkerRuntime.depsOverride = null
        TaskWorkerRuntime.install(TaskRegistry())
    }
}
