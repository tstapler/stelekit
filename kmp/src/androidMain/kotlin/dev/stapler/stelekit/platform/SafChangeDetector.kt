package dev.stapler.stelekit.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Detects external changes to a SAF-backed graph folder.
 *
 * Three detection strategies run in parallel:
 * 1. When [realGraphPath] is non-null (MANAGE_EXTERNAL_STORAGE granted): a
 *    [FileObserver] backed by inotify — fires within milliseconds, no polling needed.
 * 2. Otherwise: ContentObserver on the tree root's children URI + 30-second polling
 *    fallback for providers that don't deliver ContentObserver reliably.
 * 3. [ProcessLifecycleOwner] ON_START observer — fires immediately when the app is
 *    foregrounded so external changes (e.g. from Termux) are picked up on resume
 *    without waiting up to 30 seconds for the next poll cycle.
 */
class SafChangeDetector(
    private val context: Context,
    private val treeUri: Uri,
    private val onExternalChange: () -> Unit,
    private val realGraphPath: String? = null,
) {
    private var contentObserver: ContentObserver? = null
    private var pollingJob: Job? = null
    private var fileObserver: FileObserver? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    @Suppress("MagicNumber")
    fun start(scope: CoroutineScope) {
        if (realGraphPath != null) {
            startFileObserver(realGraphPath)
        } else {
            startContentObserverAndPoller(scope)
        }
        startForegroundObserver()
    }

    @Suppress("MagicNumber")
    private fun startFileObserver(graphPath: String) {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        val mainHandler = Handler(Looper.getMainLooper())
        val observer = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(File(graphPath), mask) {
                override fun onEvent(event: Int, path: String?) {
                    mainHandler.post { onExternalChange() }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(graphPath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    mainHandler.post { onExternalChange() }
                }
            }
        }
        fileObserver = observer
        observer.startWatching()
    }

    private fun startContentObserverAndPoller(scope: CoroutineScope) {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootDocUri, treeDocId)

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onExternalChange()
            }
        }.also { obs ->
            context.contentResolver.registerContentObserver(childrenUri, true, obs)
        }

        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                onExternalChange()
            }
        }
    }

    /** Fires an immediate scan whenever the app comes to the foreground. */
    private fun startForegroundObserver() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onExternalChange()
            }
        }
        lifecycleObserver = observer
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        pollingJob?.cancel()
        pollingJob = null
        lifecycleObserver?.let { obs ->
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            }
        }
        lifecycleObserver = null
    }
}
