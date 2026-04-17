package dev.stapler.stelekit.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Detects external changes to a SAF-backed graph folder.
 *
 * Two detection mechanisms:
 * 1. ContentObserver on the tree root's children URI — fires within seconds of a change
 * 2. 30-second polling fallback for providers that don't deliver ContentObserver reliably
 */
class SafChangeDetector(
    private val context: Context,
    private val treeUri: Uri,
    private val onExternalChange: () -> Unit
) {
    private var contentObserver: ContentObserver? = null
    private var pollingJob: Job? = null

    fun start(scope: CoroutineScope) {
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

    fun stop() {
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        pollingJob?.cancel()
        pollingJob = null
    }
}
