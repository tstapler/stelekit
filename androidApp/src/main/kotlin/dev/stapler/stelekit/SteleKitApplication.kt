package dev.stapler.stelekit

import android.app.Application
import android.util.Log
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.CredentialStore
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.platform.WriteBehindQueue
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry
import dev.stapler.stelekit.app.BuildConfig
import dev.stapler.stelekit.performance.BuildInfo
import dev.stapler.stelekit.platform.measurement.ble.KableBleScanner
import dev.stapler.stelekit.platform.sensor.AndroidCameraProvider
import dev.stapler.stelekit.platform.sensor.ARCoreDepthProvider
import dev.stapler.stelekit.platform.sensor.SensorModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class SteleKitApplication : Application() {

    /** Process-scoped scope for widget/tile background work (goAsync pattern). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Resolves when the startup write-behind flush completes (or immediately if SAF mode is
     * not active). Passed to [GraphManager] so the driver is never opened before dirty pages
     * from a previous session are flushed — without blocking the main thread.
     */
    lateinit var startupFlushJob: Deferred<Unit>
        private set

    /**
     * Shared [PlatformFileSystem] for the process. Initialized with [applicationContext]
     * (no folder-picker callback). [MainActivity] re-inits this with its picker callback
     * so the UI can launch the document tree picker.
     */
    lateinit var fileSystem: PlatformFileSystem
        private set

    /**
     * Process-scoped [GraphManager]. Null if initialization failed (e.g., corrupt keystore).
     * All Android components (widget, tile, share target) must null-check before use and
     * show a "No graph — open SteleKit" placeholder.
     */
    var graphManager: GraphManager? = null
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            BuildInfo.commitHash = BuildConfig.GIT_COMMIT_HASH
            BuildInfo.appVersion = BuildConfig.VERSION_NAME
            SteleKitContext.init(this)
            DriverFactory.setContext(this)
            CredentialStore.init(this)
            SensorModule.cameraProvider = AndroidCameraProvider(applicationContext)
            SensorModule.depthSensorProvider = ARCoreDepthProvider(applicationContext)
            MeasurementDeviceRegistry.register(KableBleScanner(applicationContext))
            fileSystem = PlatformFileSystem().apply { init(applicationContext) }
            // Activate write-behind when MANAGE_EXTERNAL_STORAGE is not granted.
            // Direct access (when granted) is faster than write-behind and makes it unnecessary.
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                val queueFile = java.io.File(filesDir, "write_behind_queue.txt")
                fileSystem.setWriteBehindQueue(WriteBehindQueue(queueFile))
                // Startup recovery: flush any pages left dirty from a previous session.
                // Runs off the main thread; GraphManager awaits this deferred before opening
                // the database so consistency is preserved without blocking onCreate().
                startupFlushJob = appScope.async(Dispatchers.IO) {
                    try { fileSystem.flushPendingWrites() }
                    catch (e: Exception) { Log.w(TAG, "Startup write-behind flush failed", e) }
                }
            } else {
                startupFlushJob = CompletableDeferred<Unit>().also { it.complete(Unit) }
            }
            graphManager = GraphManager(
                platformSettings = PlatformSettings(),
                driverFactory = DriverFactory(),
                fileSystem = fileSystem,
                preFlightJob = startupFlushJob,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Application init failed — widget/tile/share will show placeholder", e)
            if (!::fileSystem.isInitialized) {
                fileSystem = PlatformFileSystem()
            }
        }
    }

    companion object {
        private const val TAG = "SteleKitApplication"
    }
}
