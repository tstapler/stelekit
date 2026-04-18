package dev.stapler.stelekit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.domain.UrlFetcherAndroid
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.app.BuildConfig
import dev.stapler.stelekit.voice.AndroidAudioRecorder
import dev.stapler.stelekit.voice.VoicePipelineConfig
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.voice.WhisperSpeechToTextProvider
import dev.stapler.stelekit.voice.buildVoicePipeline
import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingFolderPick: CompletableDeferred<String?>? = null

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

        SteleKitContext.init(this)
        DriverFactory.setContext(this)

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
                }
            }
            val audioRecorder = remember { AndroidAudioRecorder(this@MainActivity.applicationContext) }
            val voiceSettings = remember { VoiceSettings(PlatformSettings()) }
            var voicePipeline by remember { mutableStateOf(buildVoicePipeline(audioRecorder, voiceSettings)) }
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = fileSystem.getDefaultGraphPath(),
                urlFetcher = UrlFetcherAndroid(),
                voicePipeline = voicePipeline,
                voiceSettings = voiceSettings,
                onRebuildVoicePipeline = { voicePipeline = buildVoicePipeline(audioRecorder, voiceSettings) },
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** Authority for AOSP ExternalStorageProvider — the only provider supported in v1. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
