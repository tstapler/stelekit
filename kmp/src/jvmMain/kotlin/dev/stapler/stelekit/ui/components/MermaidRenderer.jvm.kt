package dev.stapler.stelekit.ui.components

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull

/** Shared [MermaidJvmEngine]/[MermaidEngineActor] instance backing [renderMermaid] on Desktop. */
private val mermaidEngineActor = MermaidEngineActor()

/**
 * A genuine hang inside [MermaidJvmEngine.render] (e.g. a pathological Mermaid parser input) is a
 * plain blocking call with no suspension points `withTimeoutOrNull` can cooperatively cancel.
 * Launching it on this independent scope — rather than as a structured child directly inside
 * `withTimeoutOrNull` — lets [Deferred.await]'s own cancellation return control to the caller
 * promptly on timeout, without waiting for the blocked call to actually finish. The blocked thread
 * itself leaks in the background until the process exits: an accepted tradeoff (one leaked thread
 * on a rare pathological input) over freezing the caller forever. Confirmed necessary by observing
 * `withTimeoutOrNull(...) { withContext(Dispatchers.IO) { engine.render(source) } }` (the original
 * approach) hang indefinitely against a `Thread.sleep(Long.MAX_VALUE)` fake engine — that pattern
 * still waits for the structured child job to physically return, so it never actually times out.
 *
 * Backed by a single dedicated thread, not [kotlinx.coroutines.Dispatchers.IO]'s pool: GraalJS's
 * `Context` rejects concurrent multi-thread access (see [MermaidEngineActor]'s docs), so every
 * `engine.render()` call — including a would-be-concurrent one arriving while an earlier call is
 * abandoned-but-still-running after a timeout — must still funnel through one physical thread.
 */
private val blockingRenderDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "MermaidBlockingRender").apply { isDaemon = true }
}.asCoroutineDispatcher()
private val blockingRenderScope = CoroutineScope(SupervisorJob() + blockingRenderDispatcher)

/**
 * Renders [key] using the given [engine] directly. Exposed as `internal` (not `private`)
 * specifically so tests can pass a fake [MermaidJvmEngine], bypassing [MermaidEngineActor]
 * entirely — see `MermaidRendererFallbackTest.renderMermaidWith_should_returnFailed_when_engineRenderExceedsTimeout`.
 */
internal suspend fun renderMermaidWith(engine: MermaidJvmEngine, key: MermaidRenderKey): MermaidRenderResult {
    val source = key.sourceText
    if (source.isBlank()) return MermaidRenderResult.Failed("empty source")
    if (source.length > MAX_MERMAID_SOURCE_LENGTH) {
        return MermaidRenderResult.Failed("source exceeds $MAX_MERMAID_SOURCE_LENGTH chars")
    }

    return try {
        val deferred = blockingRenderScope.async { engine.render(source) }
        val svg = withTimeoutOrNull(MERMAID_RENDER_TIMEOUT_MS) { deferred.await() }
            ?: run {
                deferred.cancel()
                return MermaidRenderResult.Failed("timeout")
            }
        MermaidRenderResult.Rendered(svg)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MermaidRenderResult.Failed(e.message ?: (e::class.simpleName ?: "unknown render failure"))
    }
}

actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult =
    mermaidEngineActor.render(key)
