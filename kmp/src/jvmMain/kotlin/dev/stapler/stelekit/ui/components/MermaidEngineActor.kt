package dev.stapler.stelekit.ui.components

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes all access to a shared [MermaidJvmEngine]'s GraalJS `Context` through a dedicated
 * single-threaded dispatcher, mirroring `db/DatabaseWriteActor.kt`'s "one stateful resource, many
 * concurrent coroutine callers" pattern.
 *
 * GraalJS `Context` rejects concurrent multi-thread access by default (throws
 * `IllegalStateException: Multi threaded access requested` if two threads ever call into it at
 * once) — a multi-threaded pool like [dev.stapler.stelekit.coroutines.PlatformDispatcher.IO]
 * would eventually trigger that under two mermaid blocks visible on screen at the same time. A
 * dedicated single-threaded dispatcher makes concurrent access structurally impossible: only one
 * coroutine at a time ever runs on it, so calls are naturally queued rather than racing.
 *
 * Owns its own [CoroutineScope] — never a caller-supplied `rememberCoroutineScope()`, per this
 * repo's coroutine-scope-ownership rule — so it survives Compose recomposition/cancellation.
 * Callers must call [close] to release the dispatcher's thread and the underlying GraalJS
 * `Context` when the actor is no longer needed.
 */
class MermaidEngineActor(
    private val engine: MermaidJvmEngine = MermaidJvmEngine(),
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MermaidEngineActor").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // renderMermaidWith's own withTimeoutOrNull hops onto Dispatchers.IO so a genuine hang can be
    // abandoned (see that function's docs) — which means dispatcher-thread-affinity alone can't
    // guarantee serialization: the dedicated dispatcher's one thread would otherwise sit free to
    // start a second call's IO-dispatched render() while the first is still in flight. The Mutex
    // closes that gap by serializing at the call level, independent of which thread each call's
    // work happens to run on.
    private val mutex = Mutex()

    /** Renders [key] on the actor's dedicated thread, one call at a time; delegates to [renderMermaidWith]. */
    suspend fun render(key: MermaidRenderKey): MermaidRenderResult =
        mutex.withLock {
            withContext(scope.coroutineContext) {
                renderMermaidWith(engine, key)
            }
        }

    fun close() {
        dispatcher.close()
    }
}
