package dev.stapler.stelekit

import android.Manifest
import android.content.ComponentCallbacks2
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.domain.UrlFetcherAndroid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.ui.StelekitApp
import android.speech.SpeechRecognizer
import androidx.compose.runtime.LaunchedEffect
import dev.stapler.stelekit.voice.AndroidAudioRecorder
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerProvider
import dev.stapler.stelekit.voice.MlKitLlmFormatterProvider
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.voice.buildVoicePipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var graphManager: GraphManager? = null
    private var onMemoryPressureHandler: (() -> Unit)? = null
    private var pendingFolderPick: CompletableDeferred<String?>? = null
    private var pendingMicPermission: CompletableDeferred<Boolean>? = null
    private var pendingSaveFile: CompletableDeferred<String?>? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMicPermission?.complete(granted)
        pendingMicPermission = null
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        pendingSaveFile?.complete(uri?.toString())
        pendingSaveFile = null
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
        fileSystem.initSaveFilePicker { suggestedName, _ -> // mimeType fixed at "application/json" via CreateDocument constructor
            val deferred = CompletableDeferred<String?>()
            pendingSaveFile = deferred
            runOnUiThread { saveFileLauncher.launch(suggestedName) }
            deferred.await()
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
            val mlKitProvider = remember { MlKitLlmFormatterProvider.create() }
            var deviceLlmAvailable by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                deviceLlmAvailable = mlKitProvider?.checkEligible() ?: false
            }
            fun buildPipeline() = buildVoicePipeline(
                audioRecorder,
                voiceSettings,
                if (deviceSttAvailable && voiceSettings.getUseDeviceStt()) deviceSttProvider else null,
                if (deviceLlmAvailable && voiceSettings.getUseDeviceLlm()) mlKitProvider else null,
            )
            var voicePipeline by remember { mutableStateOf(buildPipeline()) }
            LaunchedEffect(deviceLlmAvailable) {
                voicePipeline = buildPipeline()
            }
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = fileSystem.getDefaultGraphPath(),
                graphManager = app.graphManager,
                urlFetcher = UrlFetcherAndroid(),
                voicePipeline = voicePipeline,
                voiceSettings = voiceSettings,
                onRebuildVoicePipeline = { voicePipeline = buildPipeline() },
                deviceSttAvailable = deviceSttAvailable,
                deviceLlmAvailable = deviceLlmAvailable,
                onGraphManagerReady = { gm -> graphManager = gm },
                onMemoryPressure = { handler -> onMemoryPressureHandler = handler },
            )
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

    companion object {
        private const val TAG = "MainActivity"
        /** Authority for AOSP ExternalStorageProvider — the only provider supported in v1. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
