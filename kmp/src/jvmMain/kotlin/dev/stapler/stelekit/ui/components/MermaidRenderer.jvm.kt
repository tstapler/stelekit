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

/** [MermaidRenderResult.Failed.reason] used specifically for the timeout path — [MermaidEngineActor] matches on
 * this exact value to detect a hang and swap in a fresh [MermaidJvmEngine], so keep it in sync with that check. */
internal const val MERMAID_TIMEOUT_REASON = "timeout"

/**
 * A single-thread dispatcher lets [Deferred.await]'s cancellation abandon a hung, non-suspending
 * [MermaidJvmEngine.render] call promptly on timeout. Created fresh **per call**, never shared: a
 * shared dispatcher's one worker thread would stay wedged inside the abandoned call forever,
 * silently starving every later render for the rest of the process's life. [MermaidEngineActor]
 * separately abandons the (now similarly poisoned) engine/GraalJS `Context` a timed-out call used.
 */
private fun newBlockingRenderScope(): Pair<java.util.concurrent.ExecutorService, CoroutineScope> {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MermaidBlockingRender").apply { isDaemon = true }
    }
    return executor to CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
}

/**
 * Renders [key] using the given [engine] directly. Exposed as `internal` (not `private`)
 * specifically so tests can pass a fake [MermaidJvmEngine], bypassing [MermaidEngineActor]
 * entirely — see `MermaidRendererFallbackTest.renderMermaidWith_should_returnFailed_when_engineRenderExceedsTimeout`.
 */
internal suspend fun renderMermaidWith(
    engine: MermaidJvmEngine,
    key: MermaidRenderKey,
    timeoutMs: Long = MERMAID_RENDER_TIMEOUT_MS,
): MermaidRenderResult {
    key.sourceLengthFailure()?.let { return it }
    val source = key.sourceText

    val (executor, scope) = newBlockingRenderScope()
    var hung = false
    try {
        val deferred = scope.async { engine.render(source) }
        val svg = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                deferred.cancel()
                hung = true
                return MermaidRenderResult.Failed(MERMAID_TIMEOUT_REASON)
            }
        return MermaidRenderResult.Rendered(svg)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return MermaidRenderResult.Failed(e.message ?: (e::class.simpleName ?: "unknown render failure"))
    } finally {
        // A hung call's single worker thread never returns — shutdownNow() would just mark it for
        // interrupt with no guarantee the blocking GraalJS call ever responds; leaking the executor
        // (not calling shutdown at all) is simpler and no worse. Only the non-hang paths, where the
        // worker thread has actually finished and is idle, get a clean shutdown.
        if (!hung) executor.shutdown()
    }
}

actual suspend fun renderMermaid(key: MermaidRenderKey): MermaidRenderResult =
    mermaidEngineActor.render(key)

/**
 * Best-effort warm-up for the shared [mermaidEngineActor]: GraalJS evaluates the full bundle
 * and runs `mermaid.initialize()` on the first render (ADR-001 measured 1.3–5.6s cold), so the
 * first user-visible diagram would otherwise pay that cost under its watchdog budget. Warming
 * once at app start (background) means real renders land on an initialized `Context`.
 *
 * Never throws — warm-up must not affect startup. A timed-out warm-up still swaps in a fresh
 * engine via the actor, so the next real render simply re-warms.
 */
suspend fun warmMermaidEngine() {
    runCatching {
        mermaidEngineActor.render(MermaidRenderKey("graph TD; Warm-->Up", ThemeFingerprint(true, 1), 400))
    }
}
