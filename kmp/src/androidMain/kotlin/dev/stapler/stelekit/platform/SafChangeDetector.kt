package dev.stapler.stelekit.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
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
 * 1. When [realGraphPath] is non-null (MANAGE_EXTERNAL_STORAGE granted): two
 *    [FileObserver] instances backed by inotify, one each for the `pages/` and
 *    `journals/` subdirectories — fires within milliseconds. FileObserver is not
 *    recursive, so watching the graph root alone misses file changes in subdirs.
 * 2. Otherwise: ContentObserver registered on the children URIs of the `pages/` and
 *    `journals/` document nodes (not the tree root) + 30-second polling fallback for
 *    providers that don't deliver ContentObserver reliably. Registering on the root
 *    children URI does not fire for file changes in subdirectories because
 *    `notifyForDescendants` uses string-prefix matching and the subdirectory file URIs
 *    don't share a prefix with the root children URI.
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
    private val contentObservers = mutableListOf<ContentObserver>()
    private var pollingJob: Job? = null
    private val fileObservers = mutableListOf<FileObserver>()
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    companion object {
        private const val TAG = "SafChangeDetector"
    }

    @Suppress("MagicNumber")
    fun start(scope: CoroutineScope) {
        if (realGraphPath != null) {
            startFileObservers(realGraphPath)
        } else {
            startContentObserversAndPoller(scope)
        }
        startForegroundObserver()
    }

    @Suppress("MagicNumber")
    private fun startFileObservers(graphPath: String) {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        val mainHandler = Handler(Looper.getMainLooper())

        // Watch pages/ and journals/ explicitly — FileObserver wraps a single inotify
        // watch on one directory inode and does not recurse into subdirectories.
        val dirsToWatch = listOf(File(graphPath, "pages"), File(graphPath, "journals"))
            .filter { it.isDirectory }

        for (dir in dirsToWatch) {
            val observer = if (Build.VERSION.SDK_INT >= 29) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        mainHandler.post { onExternalChange() }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        mainHandler.post { onExternalChange() }
                    }
                }
            }
            observer.startWatching()
            fileObservers.add(observer)
        }
        Log.d(TAG, "startFileObservers: watching ${dirsToWatch.size} subdirs under $graphPath")
    }

    private fun startContentObserversAndPoller(scope: CoroutineScope) {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val handler = Handler(Looper.getMainLooper())

        // Register on the children URIs of pages/ and journals/ directly.
        // The root children URI only fires for root-level changes (new subdirs), not for
        // file changes within subdirectories. SAF document IDs for ExternalStorageProvider
        // follow the pattern "{volumeId}:{relativePath}", so subdirectory doc IDs are
        // "$treeDocId/pages" and "$treeDocId/journals".
        val subdirDocIds = listOf("$treeDocId/pages", "$treeDocId/journals")
        for (docId in subdirDocIds) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { onExternalChange() }
            }
            try {
                context.contentResolver.registerContentObserver(childrenUri, true, observer)
                contentObservers.add(observer)
            } catch (e: Exception) {
                Log.w(TAG, "startContentObserversAndPoller: failed to register for $docId", e)
            }
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
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        contentObservers.forEach { context.contentResolver.unregisterContentObserver(it) }
        contentObservers.clear()
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
