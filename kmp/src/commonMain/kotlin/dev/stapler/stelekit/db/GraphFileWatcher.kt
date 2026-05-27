package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Monitors a graph directory for external file changes and notifies callers.
 *
 * Owns a private [CoroutineScope] (SupervisorJob + Default dispatcher) as required
 * by the project's long-lived-class rule — callers must never supply a scope.
 *
 * Two detection mechanisms run concurrently:
 * - A 5-second polling fallback that compares mod-times via [FileRegistry].
 * - A platform-native fast path (e.g. Android ContentObserver) triggered via
 *   [FileSystem.startExternalChangeDetection].
 *
 * Callers supply two callbacks:
 * - [readFile]: reads (and optionally decrypts) a file from disk.
 * - [onReloadFile]: called when a file change should be imported into the DB.
 *
 * @param fileSystem   Platform filesystem abstraction.
 * @param fileRegistry Shared mod-time / content-hash registry.
 * @param readFile     Reads (and decrypts) a file; returns null on failure.
 * @param onReloadFile Called to import a changed file into the database.
 */
class GraphFileWatcher(
    private val fileSystem: FileSystem,
    private val fileRegistry: FileRegistry,
    private val readFile: (filePath: String) -> String?,
    private val onReloadFile: suspend (filePath: String, content: String) -> Unit,
    private val pollIntervalMs: Long = 5_000L,
) {
    private val logger = Logger("GraphFileWatcher")

    // Owns its scope — never accepts a caller-supplied scope (project coroutine rule).
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private var watcherJob: Job? = null

    /**
     * Emitted when the file watcher detects an external modification to a file.
     * Consumers (e.g. StelekitViewModel) can collect this flow and decide whether to
     * treat the change as a conflict.  If the event's [suppress] callback is invoked
     * before the next watcher tick, the automatic re-import is skipped.
     */
    private val _externalFileChanges = MutableSharedFlow<ExternalFileChange>(extraBufferCapacity = 8)
    val externalFileChanges: SharedFlow<ExternalFileChange> = _externalFileChanges.asSharedFlow()

    // Mutex protecting gitMergeSuppressedFiles. The set is accessed concurrently by the
    // 5-second polling loop and native-change callbacks — on JVM, unsynchronized HashSet
    // mutation can corrupt the structure.
    //
    // Single-shot suppression (subscriber calling suppress() on an ExternalFileChange event)
    // does not use this mutex — it uses a local var per emission instead, removing the
    // need for runBlocking in the suppress lambda.
    private val suppressMutex = Mutex()

    // Paths added by beginGitMerge() — kept for sticky suppression across watcher ticks.
    // ALL accesses must be inside suppressMutex.withLock { }.
    private val gitMergeSuppressedFiles = mutableSetOf<String>()

    /**
     * Starts watching [graphPath] for file changes.
     * Cancels any previous watcher before starting a new one.
     */
    fun startWatching(graphPath: String) {
        fileSystem.stopExternalChangeDetection()
        watcherJob?.cancel()
        val externalChangeTrigger = Channel<Unit>(Channel.CONFLATED)
        watcherJob = scope.launch {
            // 5-second polling fallback
            launch {
                logger.info("Started watching graph for changes: $graphPath")
                while (isActive) {
                    try {
                        delay(pollIntervalMs)
                        val pagesDir = "$graphPath/pages"
                        val journalsDir = "$graphPath/journals"

                        checkDirectoryForChanges(pagesDir)
                        checkDirectoryForChanges(journalsDir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Error in graph watcher", e)
                    }
                }
            }
            // Platform-native fast-path (e.g. Android ContentObserver).
            // Channel.CONFLATED coalesces rapid callback storms into at most one
            // pending scan so we never queue up redundant directory scans.
            launch {
                for (ignored in externalChangeTrigger) {
                    try {
                        checkDirectoryForChanges("$graphPath/pages")
                        checkDirectoryForChanges("$graphPath/journals")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Error in external change handler", e)
                    }
                }
            }
        }
        fileSystem.startExternalChangeDetection(scope) {
            externalChangeTrigger.trySend(Unit)
        }
    }

    /** Stops the watcher job without cancelling the owned scope. */
    fun stopWatching() {
        fileSystem.stopExternalChangeDetection()
        watcherJob?.cancel()
        watcherJob = null
    }

    /**
     * Stops the watcher and cancels the owned [CoroutineScope].
     * Must be called when the watcher is permanently disposed (e.g. graph closed).
     */
    fun close() {
        stopWatching()
        scope.cancel()
    }

    /**
     * Adds [pathsBeingMerged] to the sticky git-merge suppression set so the 5-second
     * polling watcher ignores changes to these files during a git merge operation.
     *
     * Unlike single-shot suppression, these entries persist across multiple watcher
     * ticks until [endGitMerge] is called.
     *
     * Call this immediately before [GraphLoader.reloadFiles] after git merge completes.
     * Always paired with [endGitMerge].
     */
    suspend fun beginGitMerge(pathsBeingMerged: List<String>) {
        suppressMutex.withLock { gitMergeSuppressedFiles.addAll(pathsBeingMerged) }
    }

    /**
     * Clears the git-merge suppression set, restoring normal file-watcher behaviour.
     * Must be called after [GraphLoader.reloadFiles] completes (or if merge is aborted).
     */
    suspend fun endGitMerge() {
        suppressMutex.withLock { gitMergeSuppressedFiles.clear() }
    }

    private suspend fun checkDirectoryForChanges(dirPath: String) {
        val changeSet = fileRegistry.detectChanges(dirPath)

        for (changed in changeSet.newFiles) {
            logger.info("New file detected: ${changed.entry.filePath}")
            fileSystem.invalidateShadow(changed.entry.filePath)
            val content = if (changed.entry.filePath.endsWith(".md.stek")) {
                readFile(changed.entry.filePath) ?: continue
            } else {
                changed.content
            }
            onReloadFile(changed.entry.filePath, content)
        }

        for (changed in changeSet.changedFiles) {
            logger.info("File modification detected: ${changed.entry.filePath}")
            fileSystem.invalidateShadow(changed.entry.filePath)

            // Sticky git-merge suppression: if the path was added by beginGitMerge,
            // skip it without consuming the entry (it remains suppressed until endGitMerge).
            val isMergeSuppressed = suppressMutex.withLock { gitMergeSuppressedFiles.contains(changed.entry.filePath) }
            if (isMergeSuppressed) {
                logger.debug("Skipping watcher reload for git-merge-suppressed file: ${changed.entry.filePath}")
                continue
            }

            // For encrypted files, do NOT buffer the decrypted content in the SharedFlow.
            // Emit with empty content; decrypt on-demand only if the change is not suppressed.
            // This prevents up to 8 decrypted pages from sitting in the SharedFlow heap buffer.
            val emitContent = if (changed.entry.filePath.endsWith(".md.stek")) "" else changed.content

            // Emit event so subscribers can suppress the re-import.
            // Channel.RENDEZVOUS gives a synchronous rendezvous: the suppress() lambda sends
            // true on the channel, and withTimeoutOrNull(200L) waits up to 200ms for it.
            // SharedFlow.tryEmit is non-synchronous (buffers the event), so the old
            // var suppressed / yield() pattern was always false.  The Channel approach is
            // correct: suppress() is called synchronously by the subscriber, trySend is
            // instant, and receive() picks it up within the timeout window.
            val suppressChannel = Channel<Boolean>(Channel.RENDEZVOUS)
            val emitted = _externalFileChanges.tryEmit(ExternalFileChange(changed.entry.filePath, emitContent) {
                suppressChannel.trySend(true)
            })
            if (!emitted) {
                logger.warn("External change event dropped (buffer full): ${changed.entry.filePath}")
            }
            val suppressed = withTimeoutOrNull(200L) { suppressChannel.receive() } == true
            if (suppressed) {
                continue
            }

            val content = if (changed.entry.filePath.endsWith(".md.stek")) {
                readFile(changed.entry.filePath) ?: continue
            } else {
                changed.content
            }
            onReloadFile(changed.entry.filePath, content)
        }

        for (filePath in changeSet.deletedPaths) {
            logger.info("File deletion detected: $filePath")
        }
    }
}
