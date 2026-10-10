package dev.stapler.stelekit.capture

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.ReadyGraph
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ActiveTargetWriter
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.TargetWriter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The open graph's editor-side objects a share write must go through, so it sees the editor's unsaved edits. */
class ActiveWriteBinding(
    val graphId: GraphId,
    val graphWriter: GraphWriter,
    val fileSystem: FileSystem,
    val graphPath: String,
    /** True when the editor has unsaved or debounced edits for the page. */
    val isPageDirty: suspend (PageUuid) -> Boolean,
)

/** Set by the open graph's composition root, read by the share router. Null while no editor is composed. */
class ActiveWriteHooks {
    private val _binding = MutableStateFlow<ActiveWriteBinding?>(null)
    val binding: StateFlow<ActiveWriteBinding?> = _binding.asStateFlow()

    fun register(binding: ActiveWriteBinding) { _binding.value = binding }

    /** Clears only if [binding] is still current, so a late dispose cannot drop the next graph's binding. */
    fun unregister(binding: ActiveWriteBinding) { _binding.compareAndSet(binding, null) }
}

/**
 * Builds the real [ActiveTargetWriter] for the router's ready pair. With an [ActiveWriteHooks] binding
 * for that graph it shares the editor's writer and dirty check; without one (editor not composed yet)
 * it uses a plain [GraphWriter], as the open-graph capture path does. A repo set without a write actor
 * or a known path cannot host the writer, so the share is refused as retryable.
 */
internal fun activeTargetWriterFor(
    ready: ReadyGraph,
    hooks: ActiveWriteHooks,
    graphFileSystem: FileSystem,
    graphManager: GraphManager,
): TargetWriter {
    val bound = hooks.binding.value?.takeIf { it.graphId == ready.id }
    val path = bound?.graphPath ?: graphManager.getActiveGraphInfo()?.takeIf { it.id == ready.id }?.path
    val actor = ready.repoSet.writeActor
    if (path == null || actor == null) return RefusingWriter(ready.id)
    val fs = bound?.fileSystem ?: graphFileSystem
    val writer = bound?.graphWriter ?: GraphWriter(fs, writeActor = actor)
    return if (bound != null) {
        ActiveTargetWriter.forGraph(ready.repoSet, writer, fs, path, isPageDirty = bound.isPageDirty)
    } else {
        ActiveTargetWriter.forGraph(ready.repoSet, writer, fs, path)
    }
}

private class RefusingWriter(private val graphId: GraphId) : TargetWriter {
    private fun busy() = DomainError.MergeError.Retryable(
        "${graphId.value} is open but not writable yet; retry through the open-graph path",
    ).left()

    override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = busy()
    override suspend fun write(page: PageKey, merged: MergePage) = busy()
    override suspend fun deletePageFile(page: PageKey, expectedHash: String) = busy()
    override suspend fun fileHash(page: PageKey) = busy()
    override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) = busy()
}
