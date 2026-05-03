package dev.stapler.stelekit

import android.app.Application
import android.util.Log
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.CredentialStore
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
