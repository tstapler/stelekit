package dev.stapler.stelekit

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.domain.UrlFetcherAndroid
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.buildLlmProviderRegistry
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.git.AndroidGitRepository
import dev.stapler.stelekit.git.GitSyncServiceRegistry
import dev.stapler.stelekit.service.rememberAndroidMediaAttachmentService
import dev.stapler.stelekit.ui.StelekitApp
import android.speech.SpeechRecognizer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.stapler.stelekit.performance.OtelExporterConfig
import dev.stapler.stelekit.performance.OtelProvider
import dev.stapler.stelekit.performance.createAndroidSpanRecorder
import dev.stapler.stelekit.voice.AndroidAudioRecorder
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerProvider
import dev.stapler.stelekit.voice.VoicePipelineConfig
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.voice.buildVoicePipeline
import dev.stapler.stelekit.platform.google.AndroidGoogleAuthManager
import dev.stapler.stelekit.platform.sensor.AndroidCameraProvider
import dev.stapler.stelekit.platform.sensor.SensorModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var graphManager: GraphManager? = null
    private var onMemoryPressureHandler: (() -> Unit)? = null
    private var pendingFolderPick: CompletableDeferred<String?>? = null
    private var pendingMicPermission: CompletableDeferred<Boolean>? = null
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null
    private var pendingSaveFile: CompletableDeferred<String?>? = null
    private var pendingFilePick: CompletableDeferred<String?>? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMicPermission?.complete(granted)
        pendingMicPermission = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingCameraPermission?.complete(granted)
        pendingCameraPermission = null
    }

    private val saveFileLauncher = registerForActivityResult(
        // "*/*" lets the provider infer MIME type from the file extension. A fixed
        // "application/json" prevents some Download providers from opening an output
        // stream for .json.gz (binary) files, causing silent 0-byte exports.
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        pendingSaveFile?.complete(uri?.toString())
        pendingSaveFile = null
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            pendingFilePick?.complete(null)
            pendingFilePick = null
            return@registerForActivityResult
        }
        try {
            val sshKeysDir = getDir("ssh_keys", Context.MODE_PRIVATE)
            sshKeysDir.mkdirs()
            val displayName = contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "ssh_key"
            val destFile = File(sshKeysDir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            pendingFilePick?.complete(destFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "filePickerLauncher: failed to copy SSH key file", e)
            pendingFilePick?.complete(null)
        }
        pendingFilePick = null
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        Log.d(TAG, "folderPicker: result treeUri=$treeUri")
        if (treeUri == null) {
            Log.d(TAG, "folderPicker: user cancelled or no URI returned")
            pendingFolderPick?.complete(null)
            pendingFolderPick = null
            return@registerForActivityResult
        }

        // Reject cloud provider URIs — only local ExternalStorageProvider is supported in v1
        val authority = treeUri.authority ?: ""
        Log.d(TAG, "folderPicker: authority=$authority")
        if (authority != EXTERNAL_STORAGE_AUTHORITY) {
            Log.w(TAG, "folderPicker: rejected non-local authority '$authority' (expected $EXTERNAL_STORAGE_AUTHORITY)")
            pendingFolderPick?.complete(null)
            pendingFolderPick = null
            return@registerForActivityResult
        }

        // 1. Take persistable permission FIRST (before completing the deferred)
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Log.d(TAG, "folderPicker: persistable permission taken for $treeUri")
        } catch (e: SecurityException) {
            Log.e(TAG, "folderPicker: failed to take persistable permission", e)
            pendingFolderPick?.complete(null)
            pendingFolderPick = null
            return@registerForActivityResult
        }

        // 2. Release old grant if switching libraries (avoids exhausting the 128-grant cap on API < 30)
        val prefs = getSharedPreferences(PlatformFileSystem.PREFS_NAME, MODE_PRIVATE)
        val oldUriStr = prefs.getString(PlatformFileSystem.KEY_SAF_TREE_URI, null)
        if (oldUriStr != null && oldUriStr != treeUri.toString()) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(oldUriStr),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Log.d(TAG, "folderPicker: released old grant for $oldUriStr")
            } catch (_: Exception) { /* best effort */ }
        }

        // 3. Persist the new URI
        prefs.edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, treeUri.toString()).apply()
        Log.d(TAG, "folderPicker: URI persisted to SharedPreferences")

        // 4. Complete the deferred with the saf:// path
        val safPath = PlatformFileSystem.toSafRoot(treeUri)
        Log.d(TAG, "folderPicker: completing with safPath=$safPath")
        pendingFolderPick?.complete(safPath)
        pendingFolderPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Re-wire camera provider with this Activity's runtime permission launcher.
        // SteleKitApplication sets a no-callback provider at process start; the callback
        // requires a registered launcher which is only available after Activity.onCreate().
        SensorModule.cameraProvider = AndroidCameraProvider(
            context = applicationContext,
            requestPermission = ::requestCameraPermission,
        )

        // Upgrade the Application's shared fileSystem with the folder-picker callback.
        // SteleKitApplication already called init(applicationContext, null) — we add the
        // picker so the main UI can launch ACTION_OPEN_DOCUMENT_TREE.
        val app = application as SteleKitApplication
        val fileSystem = app.fileSystem
        fileSystem.init(applicationContext) {
            val deferred = CompletableDeferred<String?>()
            pendingFolderPick = deferred
            val hintUri = fileSystem.getStoredTreeUri()
            runOnUiThread { folderPickerLauncher.launch(hintUri) }
            deferred.await()
        }
        fileSystem.initSaveFilePicker { suggestedName, _ -> // MIME type inferred from extension via CreateDocument("*/*")
            val deferred = CompletableDeferred<String?>()
            pendingSaveFile = deferred
            runOnUiThread { saveFileLauncher.launch(suggestedName) }
            deferred.await()
        }
        fileSystem.initFilePicker {
            val deferred = CompletableDeferred<String?>()
            pendingFilePick = deferred
            runOnUiThread { filePickerLauncher.launch(arrayOf("*/*")) }
            deferred.await()
        }

        if (!OtelProvider.isInitialized) {
            OtelProvider.initialize(OtelExporterConfig(enableStdout = false, enableRingBuffer = true))
        }

        setContent {
            val fileSystem = remember {
                PlatformFileSystem().apply {
                    init(this@MainActivity) {
                        val deferred = CompletableDeferred<String?>()
                        pendingFolderPick = deferred
                        // Pre-fill the picker with the last known folder so "Reconnect" UX is smooth
                        val hintUri = getStoredTreeUri()
                        runOnUiThread { folderPickerLauncher.launch(hintUri) }
                        deferred.await()
                    }
                    initSaveFilePicker { suggestedName, _ ->
                        val deferred = CompletableDeferred<String?>()
                        pendingSaveFile = deferred
                        runOnUiThread { saveFileLauncher.launch(suggestedName) }
                        deferred.await()
                    }
                    initFilePicker {
                        val deferred = CompletableDeferred<String?>()
                        pendingFilePick = deferred
                        runOnUiThread { filePickerLauncher.launch(arrayOf("*/*")) }
                        deferred.await()
                    }
                }
            }
            val audioRecorder = remember { AndroidAudioRecorder(this@MainActivity.applicationContext, this@MainActivity::requestMicrophonePermission) }
            val voiceSettings = remember { VoiceSettings(PlatformSettings()) }
            val deviceSttAvailable = remember { AndroidSpeechRecognizerProvider.isAvailable(this@MainActivity.applicationContext) }
            val deviceSttProvider = remember {
                if (deviceSttAvailable) AndroidSpeechRecognizerProvider(
                    this@MainActivity.applicationContext,
                    this@MainActivity::requestMicrophonePermission,
                ) else null
            }
            // LLM provider registry + settings (Epic 8) — built the same way ui/App.kt builds
            // them: a plain (non-vault) CredentialStore-backed LlmCredentialStore, plus
            // LlmSettings for per-feature provider selection. Constructed once and remembered,
            // not rebuilt on every recomposition.
            val llmCredentialStore = remember {
                LlmCredentialStore(CredentialStore())
            }
            val llmSettings = remember { LlmSettings(PlatformSettings()) }
            val llmProviderRegistry = remember(llmCredentialStore, llmSettings) {
                buildLlmProviderRegistry(llmCredentialStore, llmSettings)
            }
            var deviceLlmAvailable by remember { mutableStateOf(false) }
            var voicePipeline by remember { mutableStateOf(VoicePipelineConfig()) }

            // buildVoicePipeline is suspend (it live-checks provider availability), so this can
            // only run from a coroutine. deviceLlmAvailable mirrors the old checkEligible() flag
            // for VoiceCaptureSettings' informational UI — now derived from the on-device
            // provider's live LlmProviderAvailability instead of MlKitLlmFormatterProvider
            // directly, since the registry now owns provider construction.
            suspend fun rebuildVoicePipeline() {
                val onDeviceProvider = llmProviderRegistry.all()
                    .firstOrNull { it.kind == LlmProviderKind.ON_DEVICE }
                deviceLlmAvailable = onDeviceProvider?.checkAvailability() is LlmProviderAvailability.Available
                voicePipeline = buildVoicePipeline(
                    audioRecorder = audioRecorder,
                    settings = voiceSettings,
                    directSpeechProvider = if (deviceSttAvailable && voiceSettings.getUseDeviceStt()) deviceSttProvider else null,
                    registry = llmProviderRegistry,
                    llmSettings = llmSettings,
                )
            }
            val composeScope = rememberCoroutineScope()
            LaunchedEffect(llmProviderRegistry) {
                rebuildVoicePipeline()
            }
            val spanRecorder = remember { createAndroidSpanRecorder() }
            val gitRepository = remember {
                val ctx = this@MainActivity.applicationContext
                AndroidGitRepository(pathResolver = { PlatformFileSystem.resolveSafToRealPath(it, ctx) })
            }
            val attachmentService = rememberAndroidMediaAttachmentService(this@MainActivity, fileSystem)

            // Keep GitSyncServiceRegistry in sync so GitSyncWorker can reach the active service
            // even when the process was revived by WorkManager without the UI being foregrounded.
            val appGm = app.graphManager
            val activeGitSyncService by (appGm?.activeGitSyncService
                ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
            LaunchedEffect(activeGitSyncService) {
                if (appGm == null) return@LaunchedEffect
                val graphId = appGm.getActiveGraphId() ?: return@LaunchedEffect
                val svc = activeGitSyncService
                if (svc != null) GitSyncServiceRegistry.register(graphId.value, svc)
                else GitSyncServiceRegistry.unregister(graphId.value)
            }

            StelekitApp(
                fileSystem = fileSystem,
                // When the benchmark extra is absent and SAF permission is not yet
                // granted, pass "" so StelekitApp shows the first-launch setup screen.
                // When SAF permission is valid, getDefaultGraphPath() returns the SAF
                // path so the app opens the user's existing graph directly.
                graphPath = intent.getStringExtra(EXTRA_BENCHMARK_GRAPH_PATH)
                    ?: if (fileSystem.hasStoragePermission()) fileSystem.getDefaultGraphPath() else "",
                graphManager = app.graphManager,
                urlFetcher = UrlFetcherAndroid(),
                voicePipeline = voicePipeline,
                voiceSettings = voiceSettings,
                onRebuildVoicePipeline = { composeScope.launch { rebuildVoicePipeline() } },
                deviceSttAvailable = deviceSttAvailable,
                deviceLlmAvailable = deviceLlmAvailable,
                spanRecorder = spanRecorder,
                gitRepository = gitRepository,
                onGraphManagerReady = { gm -> graphManager = gm },
                onMemoryPressure = { handler -> onMemoryPressureHandler = handler },
                attachmentService = attachmentService,
                requestCameraPermission = ::requestCameraPermission,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth 2.0 deep-link callback: com.stelekit.app:/oauth2redirect?code=...
        val data = intent.data
        if (data?.scheme == "com.stelekit.app" && data.path == "/oauth2redirect") {
            val code = data.getQueryParameter("code") ?: return
            val state = data.getQueryParameter("state")
            // Validate CSRF state nonce before accepting the code.
            // An empty or mismatched state rejects the callback silently.
            if (state.isNullOrBlank() || state != AndroidGoogleAuthManager.pendingOAuthState) return
            AndroidGoogleAuthManager.pendingOAuthState = null
            // Emit (state, code) pair so authenticate() can filter by its own nonce,
            // discarding any stale codes from a prior auth session still in the buffer.
            AndroidGoogleAuthManager.oauthCodeFlow.tryEmit(state to code)
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush any pending write-behind pages to SAF before the process may be killed.
        val app = application as? SteleKitApplication ?: return
        lifecycleScope.launch {
            try { app.fileSystem.flushPendingWrites() }
            catch (e: Exception) { Log.w(TAG, "onStop write-behind flush failed", e) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val repos = graphManager?.activeRepositorySet?.value ?: return
        lifecycleScope.launch {
            // RUNNING_LOW (10) and above: free page caches (~10 MB on a typical device).
            // This includes UI_HIDDEN (20), BACKGROUND (40), MODERATE (60), COMPLETE (80),
            // so page caches are always released when the app is backgrounded.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                repos.pageRepository.cacheEvictAll()
            }
            // RUNNING_CRITICAL (15) and above: also release block caches (~15 MB) and
            // cancel background indexing to free parse buffers and coroutine stacks.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                repos.blockRepository.cacheEvictAll()
                onMemoryPressureHandler?.invoke()
            }
        }
    }

    private suspend fun requestMicrophonePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingMicPermission = deferred
        runOnUiThread { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        return deferred.await()
    }

    private suspend fun requestCameraPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        pendingCameraPermission?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission = deferred
        runOnUiThread { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
        return deferred.await()
    }

    companion object {
        private const val TAG = "MainActivity"
        /** Authority for AOSP ExternalStorageProvider — the only provider supported in v1. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        /**
         * Optional Intent extra that overrides the default graph path. Used by the
         * :macrobenchmark module to point the app at a pre-seeded synthetic graph
         * in /data/local/tmp/ without requiring SAF folder picker interaction.
         */
        const val EXTRA_BENCHMARK_GRAPH_PATH = "benchmark_graph_path"
    }
}
