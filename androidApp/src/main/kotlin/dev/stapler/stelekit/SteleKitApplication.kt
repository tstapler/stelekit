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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class SteleKitApplication : Application() {

    /** Process-scoped scope for widget/tile background work (goAsync pattern). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            SteleKitContext.init(this)
            DriverFactory.setContext(this)
            CredentialStore.init(this)
            fileSystem = PlatformFileSystem().apply { init(applicationContext) }
            // Activate write-behind when MANAGE_EXTERNAL_STORAGE is not granted.
            // Direct access (when granted) is faster than write-behind and makes it unnecessary.
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                val queueFile = java.io.File(filesDir, "write_behind_queue.txt")
                fileSystem.setWriteBehindQueue(WriteBehindQueue(queueFile))
                // Startup recovery: flush any pages left dirty from a previous session.
                // Runs synchronously before the graph loads to guarantee consistency.
                runBlocking {
                    try { fileSystem.flushPendingWrites() }
                    catch (e: Exception) { Log.w(TAG, "Startup write-behind flush failed", e) }
                }
            }
            graphManager = GraphManager(
                platformSettings = PlatformSettings(),
                driverFactory = DriverFactory(),
                fileSystem = fileSystem,
            )
        } catch (e: Exception) {
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
