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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects external changes to a SAF-backed graph folder.
 *
 * Three detection strategies run in parallel:
 * 1. When [realGraphPath] is non-null (MANAGE_EXTERNAL_STORAGE granted): two
 *    [FileObserver] instances backed by inotify, one each for the `pages/` and
 *    `journals/` subdirectories — fires within milliseconds. FileObserver is not
 *    recursive, so watching the graph root alone misses file changes in subdirs.
 *    On DELETE_SELF or MOVE_SELF (dead-inode events), the observer is torn down and
 *    a 2-second poll re-arms a fresh observer when the directory reappears.
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
    // Synchronized lists: onEvent() callbacks arrive on kernel inotify threads;
    // start() and stop() run on the main thread.
    private val contentObservers = Collections.synchronizedList(mutableListOf<ContentObserver>())
    private var pollingJob: Job? = null
    private val fileObservers = Collections.synchronizedList(mutableListOf<FileObserver>())
    private var lifecycleObserver: DefaultLifecycleObserver? = null
    @Volatile private var activeScope: CoroutineScope? = null
    @Volatile private var stopped = false

    /** Keyed by dir.absolutePath; cancels any prior re-arm job before launching a new one. */
    private val rearmJobs = ConcurrentHashMap<String, Job>()

    companion object {
        private const val TAG = "SafChangeDetector"

        // 2 s: short enough to catch sync-tool directory recreates within a reasonable window,
        // long enough not to spin if the directory is transiently absent during an atomic rename.
        private const val REARM_POLL_INTERVAL_MS = 2_000L
    }

    @Suppress("MagicNumber")
    fun start(scope: CoroutineScope) {
        activeScope = scope
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
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val mainHandler = Handler(Looper.getMainLooper())

        // Watch pages/ and journals/ explicitly — FileObserver wraps a single inotify
        // watch on one directory inode and does not recurse into subdirectories.
        val dirsToWatch = listOf(File(graphPath, "pages"), File(graphPath, "journals"))
            .filter { it.isDirectory }

        for (dir in dirsToWatch) {
            addObserverFor(dir, mask, mainHandler)
        }
        Log.d(TAG, "startFileObservers: watching ${dirsToWatch.size} subdirs under $graphPath")
    }

    /**
     * Creates and starts a [FileObserver] for [dir], adding it to [fileObservers].
     * On DELETE_SELF or MOVE_SELF, tears the observer down and schedules re-arm via [activeScope].
     */
    @Suppress("MagicNumber")
    private fun addObserverFor(dir: File, mask: Int, mainHandler: Handler) {
        val observer = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, dir, mask, mainHandler, this)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, dir, mask, mainHandler, this)
                }
            }
        }
        observer.startWatching()
        fileObservers.add(observer)
    }

    private fun handleFileEvent(
        event: Int,
        dir: File,
        mask: Int,
        mainHandler: Handler,
        self: FileObserver,
    ) {
        val deadInode = (event and FileObserver.DELETE_SELF != 0) or (event and FileObserver.MOVE_SELF != 0)
        if (deadInode) {
            // The watched directory inode is gone. The observer is now deaf.
            // Tear it down and re-arm once the directory reappears.
            self.stopWatching()
            fileObservers.remove(self)
            Log.d(TAG, "handleFileEvent: dead-inode on ${dir.name} — scheduling re-arm")
            val key = dir.absolutePath
            rearmJobs[key]?.cancel()
            rearmJobs[key] = activeScope?.launch(Dispatchers.IO) {
                while (isActive && !stopped && !dir.isDirectory) {
                    delay(REARM_POLL_INTERVAL_MS)
                }
                rearmJobs.remove(key)
                if (!stopped && dir.isDirectory) {
                    synchronized(fileObservers) {
                        if (!stopped) addObserverFor(dir, mask, mainHandler)
                    }
                    if (!stopped) mainHandler.post { onExternalChange() }
                    Log.d(TAG, "handleFileEvent: re-armed FileObserver for ${dir.name}")
                }
            } ?: run { rearmJobs.remove(key); null }
        } else {
            mainHandler.post { onExternalChange() }
        }
    }

    private fun startContentObserversAndPoller(scope: CoroutineScope) {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val handler = Handler(Looper.getMainLooper())

        // Register on the children URIs of pages/ and journals/ directly.
        // The root children URI only fires for root-level changes (new subdirs), not for
        // file changes within subdirectories. SAF document IDs for ExternalStorageProvider
        // follow the pattern "{volumeId}:{relativePath}", so subdirectory doc IDs are
        // "$treeDocId/pages" and "$treeDocId/journals".
        // Note: this assumes ExternalStorageProvider's "{volumeId}:{relativePath}" document ID format.
        // Other SAF providers (Google Drive, OEM file managers) use opaque IDs; those providers
        // degrade to the 30-second polling fallback for subdirectory change detection.
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
        stopped = true
        rearmJobs.values.forEach { it.cancel() }
        rearmJobs.clear()
        activeScope = null
        synchronized(fileObservers) {
            fileObservers.forEach { it.stopWatching() }
            fileObservers.clear()
        }
        synchronized(contentObservers) {
            contentObservers.forEach { context.contentResolver.unregisterContentObserver(it) }
            contentObservers.clear()
        }
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
