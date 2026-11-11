package dev.stapler.stelekit

import android.app.Application
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.platform.WriteBehindQueue
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.performance.BuildInfo
import android.content.Context
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry
import dev.stapler.stelekit.platform.measurement.ble.KableBleScanner
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDeviceFactory
import dev.stapler.stelekit.platform.measurement.usb.AndroidUsbSerialFactory
import dev.stapler.stelekit.platform.ml.OnnxMonocularDepthEstimator
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.platform.sensor.AndroidCameraFrameSource
import dev.stapler.stelekit.platform.sensor.AndroidCameraProvider
import dev.stapler.stelekit.platform.sensor.AndroidMotionSensorProvider
import dev.stapler.stelekit.platform.sensor.ARCoreDepthProvider
import dev.stapler.stelekit.platform.sensor.SensorModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import dev.stapler.stelekit.merge.sweepMergeArtifactsOnDisk
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.capture.ShareCaptureServices
import dev.stapler.stelekit.capture.createShareCaptureServices
import dev.stapler.stelekit.capture.shareInboxConfigFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Every measurement transport the Android app offers; keyboard entry is the always-available fallback. */
internal fun defaultMeasurementDeviceFactories(context: Context): List<MeasurementDeviceFactory> = listOf(
    KableBleScanner(context),
    KeyboardEmulationDeviceFactory(),
    AndroidUsbSerialFactory(context),
)

class SteleKitApplication : Application() {

    /** Process-scoped scope for widget/tile background work (goAsync pattern). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Shows the "closed unexpectedly" banner once per previous-session exit record (PR-0 diagnostics). */
    @Volatile
    var postKillBanner: dev.stapler.stelekit.platform.diagnostics.PostKillBannerController? = null
        private set

    /** 10 s resource sampler the annotation editor starts while it is composed. */
    val editorDiagnosticsSampler: dev.stapler.stelekit.platform.diagnostics.EditorDiagnosticsSampler by lazy {
        dev.stapler.stelekit.platform.diagnostics.EditorDiagnosticsSampler.create()
    }

    /**
     * The one [dev.stapler.stelekit.jobs.schedule.TaskRegistry] for the process, shared with MainActivity's
     * scheduler. Populated only in [onCreate]; a worker cold-started by WorkManager sees it after that runs.
     */
    val taskRegistry = dev.stapler.stelekit.jobs.schedule.TaskRegistry()

    /** Runs cross-graph copies in an application-owned scope so they survive Activity teardown. */
    val copyRunHost: dev.stapler.stelekit.merge.CopyRunHost = dev.stapler.stelekit.merge.AndroidCopyRunHost()

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

    /** Test seam: replaces the lazily built share pipeline. */
    internal var shareServicesOverride: ShareCaptureServices? = null
    internal var captureTargetSettingsOverride: CaptureTargetSettings? = null
    internal var offGraphReasonOverride: (suspend (dev.stapler.stelekit.model.GraphInfo) -> String?)? = null

    /** The one editor-write binding, shared by the share pipeline here and the app composition. */
    internal val activeWriteHooks = dev.stapler.stelekit.capture.ActiveWriteHooks()

    @Volatile
    private var shareServicesMemo: Pair<GraphManager, ShareCaptureServices>? = null
    private val captureSettingsLazy by lazy { CaptureTargetSettings(PlatformSettings()) }
    val recentCaptures: RecentCaptureStore by lazy { RecentCaptureStore(this) }

    fun captureTargetSettings(): CaptureTargetSettings = captureTargetSettingsOverride ?: captureSettingsLazy

    /**
     * Share pipeline (appender + inbox + drain) for the whole process, built on first use because a share
     * can cold-start it without MainActivity. MainActivity's App composition is handed this same instance
     * ([captureDeps]), so the inbox the share target writes to is the one the drain and the panel read.
     */
    fun shareServices(): ShareCaptureServices? {
        shareServicesOverride?.let { return it }
        val gm = graphManager ?: return null
        if (!::fileSystem.isInitialized) return null
        shareServicesMemo?.takeIf { it.first === gm }?.let { return it.second }
        return synchronized(this) {
            shareServicesMemo?.takeIf { it.first === gm }?.second ?: try {
                createShareCaptureServices(gm, fileSystem, shareInboxConfigFor(filesDir.absolutePath), activeWriteHooks)
                    .also {
                        shareServicesMemo = gm to it
                        it.drain.start() // idempotent; MainActivity's composition reuses this same instance
                    }
            } catch (e: Throwable) {
                logger.warn("Share pipeline unavailable", e)
                null
            }
        }
    }

    /** Capture wiring for the App composition: reuses [shareServices] and [activeWriteHooks] rather than building its own. */
    internal fun captureDeps(
        copyHost: dev.stapler.stelekit.ui.screens.copy.CopyHostConfig?,
    ): dev.stapler.stelekit.ui.StelekitAppCaptureDeps = dev.stapler.stelekit.ui.StelekitAppCaptureDeps(
        shareInbox = shareInboxConfigFor(filesDir.absolutePath),
        shareServicesProvider = { shareServices() },
        activeWriteHooks = activeWriteHooks,
        copyHost = copyHost,
    )

    /** Why [info] can't take an off-graph write right now, or null when it can. */
    suspend fun offGraphUnavailableReason(info: dev.stapler.stelekit.model.GraphInfo): String? {
        offGraphReasonOverride?.let { return it(info) }
        val services = shareServices() ?: return "this device can't save to a graph that isn't open."
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                if (!fileSystem.directoryExists(info.effectiveNotesPath.value)) return@withContext "its folder is missing."
                val storage = graphManager?.getStorageLocation(info.id.value)
                val target = dev.stapler.stelekit.merge.OffGraphTarget(
                    info.id, info.effectiveNotesPath, isActive = false, encrypted = info.isParanoidMode, storage = storage,
                )
                services.capabilities.canWriteOffGraph(target).fold({ it.userText }, { null })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Off-graph availability check failed for ${info.id.value}", e)
            "it couldn't be checked."
        }
    }

    /**
     * Logs and crash traces survive the process so the next launch can show them in the Logs
     * screen without adb/logcat. Must not throw: a failure here would break every startup.
     */
    private fun installPersistentLogging() {
        try {
            val logDir = java.io.File(filesDir, "logs")
            val sink = dev.stapler.stelekit.logging.AndroidFileLogSink(logDir)
            val exitRecords = previousExitRecords()
            val crashFile = java.io.File(logDir, dev.stapler.stelekit.logging.AndroidFileLogSink.LAST_CRASH)
            val crashFileId = if (crashFile.exists()) "crash:${crashFile.lastModified()}" else null
            val previousTail = dev.stapler.stelekit.logging.PreviousSessionLog.tail(logDir)
            dev.stapler.stelekit.logging.PreviousSessionLog.replay(
                logDir,
                exitRecords.map { dev.stapler.stelekit.platform.diagnostics.formatExitRecord(it) },
            )
            postKillBanner = dev.stapler.stelekit.platform.diagnostics.PostKillBannerController(
                dev.stapler.stelekit.platform.diagnostics.FileSeenExitRecordStore(java.io.File(logDir, "seen-exit-record")),
            ).also {
                it.offer(
                    dev.stapler.stelekit.platform.diagnostics.PreviousSessionAssessor.assess(
                        exitRecords, previousTail, crashFileId,
                    ),
                )
            }
            dev.stapler.stelekit.logging.LogManager.addSink(sink)
            dev.stapler.stelekit.logging.AndroidCrashRecorder.install(logDir)
        } catch (e: Throwable) {
            android.util.Log.w("SteleKitApplication", "Persistent logging unavailable", e)
        }
    }

    /** Why Android ended the previous processes (OOM kill, ANR, excessive CPU...), newest first; API 30+. */
    private fun previousExitRecords(): List<dev.stapler.stelekit.platform.diagnostics.ExitRecord> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(
                packageName, 0, dev.stapler.stelekit.platform.diagnostics.EXIT_RECORD_REQUEST_COUNT,
            ).map {
                dev.stapler.stelekit.platform.diagnostics.ExitRecord(
                    reason = it.reason,
                    importance = it.importance,
                    status = it.status,
                    pssKb = it.pss,
                    rssKb = it.rss,
                    timestampMs = it.timestamp,
                    description = it.description,
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        // First, so every later startup failure and every feature logger reaches logcat
        // (tags "SteleKit.<Logger tag>"); previously no sink was registered on Android.
        dev.stapler.stelekit.logging.LogManager.addSink(dev.stapler.stelekit.logging.AndroidLogSink())
        installPersistentLogging()
        try {
            dev.stapler.stelekit.platform.NetworkMonitor.init(this)
            dev.stapler.stelekit.jobs.schedule.registerAndroidTasks(taskRegistry)
            dev.stapler.stelekit.git.registerGitSyncTask(taskRegistry, cold = dev.stapler.stelekit.git.AndroidColdPathRunner(this))
            dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime.knownScopes = dev.stapler.stelekit.db.GraphRegistryKnownScopes(PlatformSettings())
            // Set here (not in MainActivity) so a cold worker, the shim and MainActivity's store all migrate git-sync identically.
            dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime.legacySeed = dev.stapler.stelekit.git.LegacyGitSyncMigration(
                PlatformSettings(), dev.stapler.stelekit.git.AndroidGitLegacyConfigSource(this),
            )
            // Debug/internal builds expose the Asset indexing switch; release variants stay hidden
            // until the heap-gate flip PR (AssetBuildFlags.RELEASE_TOGGLE_VISIBLE).
            dev.stapler.stelekit.asset.pipeline.AssetBuildFlags.internalBuild =
                applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            // The console's sql! write path follows the same rule (ConsoleBuildFlags.RELEASE_WRITES_ENABLED).
            dev.stapler.stelekit.console.ConsoleBuildFlags.internalBuild =
                dev.stapler.stelekit.asset.pipeline.AssetBuildFlags.internalBuild
            // Debug/internal builds expose Google Photos import, Drive export and Drive browsing;
            // release variants stay hidden until GoogleBuildFlags.RELEASE_VISIBLE is flipped after
            // the OAuth device pass.
            dev.stapler.stelekit.platform.google.GoogleBuildFlags.internalBuild =
                applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            BuildInfo.commitHash = getString(R.string.git_commit_hash)
            BuildInfo.appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            SteleKitContext.init(this)
            DriverFactory.setContext(this)
            CredentialStore.init(this)
            SensorModule.cameraProvider = AndroidCameraProvider(applicationContext)
            // Re-wired in MainActivity.onCreate() with the real runtime-permission launcher,
            // same as cameraProvider above — no Activity launcher is available yet at this point.
            SensorModule.cameraFrameSource = AndroidCameraFrameSource(applicationContext)
            SensorModule.depthSensorProvider = ARCoreDepthProvider(applicationContext)
            SensorModule.motionSensorProvider = AndroidMotionSensorProvider(applicationContext)
            SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext)
            defaultMeasurementDeviceFactories(applicationContext).forEach(MeasurementDeviceRegistry::register)
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
                    catch (e: Exception) { logger.warn("Startup write-behind flush failed", e) }
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
            publishShareShortcutsOnRegistryChange(graphManager)
            startScheduleStartup()
            appScope.launch(Dispatchers.IO) {
                try {
                    sweepMergeArtifactsOnDisk(filesDir.absolutePath)
                } catch (e: Throwable) {
                    logger.warn("Merge artifact sweep failed", e)
                }
            }
        } catch (e: Throwable) {
            logger.error("Application init failed — widget/tile/share will show placeholder", e)
            if (!::fileSystem.isInitialized) {
                fileSystem = PlatformFileSystem()
            }
        }
    }

    /**
     * Migrate the active graph, reconcile orphaned work and re-apply wiped work from the ledger (Story 3.1.4).
     * Off the main thread; guarded so it can never fail startup. Replaces MainActivity's own reconcile call.
     */
    private fun startScheduleStartup() {
        appScope.launch(Dispatchers.Default) {
            try {
                val settings = PlatformSettings()
                val knownScopes = dev.stapler.stelekit.db.GraphRegistryKnownScopes(settings)
                val store = dev.stapler.stelekit.jobs.schedule.TaskSettingsStore(
                    settings, dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime.legacySeed, taskRegistry,
                )
                val ledger = dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger(settings)
                val health = dev.stapler.stelekit.jobs.schedule.SettingsReadHealth(this@SteleKitApplication)
                dev.stapler.stelekit.jobs.schedule.ScheduleStartup(
                    store = store,
                    scheduler = dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler(
                        context = this@SteleKitApplication,
                        registry = taskRegistry,
                        store = store,
                        ledger = ledger,
                        workers = dev.stapler.stelekit.git.GitSyncWorkerBinding.resolver,
                    ),
                    registry = taskRegistry,
                    ledger = ledger,
                    knownScopes = knownScopes,
                    activeGraphId = { dev.stapler.stelekit.db.readActiveGraphId(settings) },
                    reconcile = {
                        dev.stapler.stelekit.jobs.schedule.ScheduledTaskReconciler(this@SteleKitApplication, knownScopes).reconcile()
                    },
                    workManagerReady = { dev.stapler.stelekit.jobs.schedule.isWorkManagerReady(this@SteleKitApplication) },
                    onSettingsUnreadable = health::recordFailure,
                ).run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Schedule startup failed", e)
            }
        }
    }

    private fun publishShareShortcutsOnRegistryChange(gm: GraphManager?) {
        gm ?: return
        appScope.launch(Dispatchers.IO) {
            try {
                gm.graphRegistry
                    .map { registry -> registry.graphs.map { it.id to it.displayName } to registry.graphs }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .collect { (_, graphs) ->
                        try {
                            ShareShortcutPublisher.publish(this@SteleKitApplication, graphs)
                        } catch (e: Exception) {
                            logger.warn("Share shortcut publish failed", e)
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Share shortcut publisher stopped", e)
            }
        }
    }

    companion object {
        private val logger = dev.stapler.stelekit.logging.Logger("SteleKitApplication")
    }
}
