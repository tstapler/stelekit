package dev.stapler.stelekit.ui.components

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** REQ-10 — [MermaidEngineActor] serializes concurrent renders onto one dedicated thread. */
class MermaidEngineActorTest {
    private fun key(n: Int) = MermaidRenderKey("graph TD; A$n-->B$n", ThemeFingerprint(true, 1), 400)

    @Test
    fun render_should_completeWithoutException_when_calledOnce() = runTest {
        val fakeEngine = object : MermaidJvmEngine() {
            override fun render(source: String): String = "<svg>ok</svg>"
        }
        val actor = MermaidEngineActor(fakeEngine)
        val result = actor.render(key(0))
        assertIs<MermaidRenderResult.Rendered>(result)
        actor.close()
    }

    @Test
    fun render_should_serializeCalls_when_invokedConcurrentlyFromTwoCoroutines() = runTest {
        val overlapDetected = AtomicBoolean(false)
        val currentlyInside = AtomicInteger(0)
        val fakeEngine = object : MermaidJvmEngine() {
            override fun render(source: String): String {
                if (currentlyInside.incrementAndGet() > 1) overlapDetected.set(true)
                try {
                    Thread.sleep(50)
                    return "<svg>$source</svg>"
                } finally {
                    currentlyInside.decrementAndGet()
                }
            }
        }
        val actor = MermaidEngineActor(fakeEngine)
        try {
            val results = listOf(
                async { actor.render(key(1)) },
                async { actor.render(key(2)) },
            ).awaitAll()
            results.forEach { assertIs<MermaidRenderResult.Rendered>(it) }
            assertFalse(overlapDetected.get(), "engine.render() calls must never overlap")
        } finally {
            actor.close()
        }
    }

    /**
     * Regression test for the bug found in review: a timed-out call's engine has a thread
     * permanently wedged inside its GraalJS Context, so reusing that same engine for later calls
     * would make every future render also time out — silently degrading rendering app-wide for the
     * rest of the process's life. [MermaidEngineActor] must swap in a fresh engine on timeout so a
     * single hang costs exactly one render, not all of them.
     */
    @Test
    fun render_should_recoverOnNextCall_when_previousCallTimedOut() = runTest(timeout = 60.seconds) {
        val hangingEngine = object : MermaidJvmEngine() {
            override fun render(source: String): String {
                Thread.sleep(Long.MAX_VALUE)
                error("unreachable")
            }
        }
        val healthyEngine = object : MermaidJvmEngine() {
            override fun render(source: String): String = "<svg>recovered</svg>"
        }
        // A fast fake for the post-swap engine, not a real MermaidJvmEngine(): a genuinely fresh
        // GraalJS Context's own cold-start cost can itself exceed MERMAID_RENDER_TIMEOUT_MS under
        // load (a separate, already-documented risk) — this test verifies the swap happens at all,
        // independent of that unrelated timing concern.
        val actor = MermaidEngineActor(hangingEngine, newEngine = { healthyEngine })
        try {
            val timedOut = actor.render(key(1))
            assertIs<MermaidRenderResult.Failed>(timedOut)
            assertEquals(MERMAID_TIMEOUT_REASON, timedOut.reason)

            val recovered = actor.render(key(2))
            assertIs<MermaidRenderResult.Rendered>(recovered)
        } finally {
            actor.close()
        }
    }

    /**
     * REQ-10 follow-up — a static [MERMAID_RENDER_TIMEOUT_MS] floor can't tell a genuinely wedged
     * `Context` apart from a render that's merely slow right now under CI CPU contention (see
     * `MermaidEngineActorTest.render_should_returnDistinctResults_when_twoRealBlocksVisibleSimultaneously`'s
     * own real-CI failure history). The actor widens its budget from recently observed latency
     * instead: this call sequence would fail outright against a static 4000ms timeout, since the
     * second call sleeps 6000ms — it only succeeds because the first (slow-but-successful) call
     * seeded an adaptive estimate comfortably above that.
     */
    @Test
    fun render_should_widenTimeoutBudget_when_priorRenderWasSlowButSucceeded() = runTest(timeout = 30.seconds) {
        val callCount = AtomicInteger(0)
        val engine = object : MermaidJvmEngine() {
            override fun render(source: String): String {
                val sleepMs = if (callCount.getAndIncrement() == 0) 2500L else 6000L
                Thread.sleep(sleepMs)
                return "<svg>$source</svg>"
            }
        }
        val actor = MermaidEngineActor(engine)
        try {
            assertIs<MermaidRenderResult.Rendered>(actor.render(key(1)))
            assertIs<MermaidRenderResult.Rendered>(actor.render(key(2)))
        } finally {
            actor.close()
        }
    }

    /**
     * Uses a real [MermaidJvmEngine]: asserts two sources render to distinct SVGs when both are
     * visible at once (the actor serializes them onto one thread rather than racing the Context).
     *
     * Real-timing test, so it runs on `runBlocking` with real timeouts — never `runTest`, whose
     * virtual clock doesn't advance while `engine.render()` blocks a real thread, making the
     * watchdog fire under CI CPU contention even when the engine would have completed. The
     * warm-up absorbs GraalJS's one-time cold-start cost (best-effort: a contended cold start
     * can itself time out and swap the engine, so it retries); the asserted pair retries on
     * `timeout` only, which under load means contention, not a wedged Context. Any non-timeout
     * failure aborts immediately since that signals a real product bug.
     */
    @Test
    fun render_should_returnDistinctResults_when_twoRealBlocksVisibleSimultaneously(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val actor = MermaidEngineActor()
            try {
                val warmKey = MermaidRenderKey("graph TD; Warm-->Up", ThemeFingerprint(true, 1), 400)
                for (attempt in 1..3) {
                    // A contended cold start can itself time out (the actor swaps in a fresh
                    // engine on timeout) — retry warm-up rather than asserting on it.
                    if (actor.render(warmKey) is MermaidRenderResult.Rendered) break
                }
                var last: List<MermaidRenderResult>? = null
                repeat(3) {
                    val results = listOf(
                        async { actor.render(MermaidRenderKey("graph TD; X-->Y", ThemeFingerprint(true, 1), 400)) },
                        async { actor.render(MermaidRenderKey("pie title P\n  \"A\" : 1", ThemeFingerprint(true, 1), 400)) },
                    ).awaitAll()
                    last = results
                    if (results.all { it is MermaidRenderResult.Rendered }) {
                        val rendered = results.filterIsInstance<MermaidRenderResult.Rendered>()
                        assertEquals(2, rendered.map { it.svg }.distinct().size)
                        return@withTimeout
                    }
                    results.filterIsInstance<MermaidRenderResult.Failed>()
                        .firstOrNull { it.reason != MERMAID_TIMEOUT_REASON }
                        ?.let { error("expected Rendered, got $it") }
                }
                error("expected Rendered, got ${last?.firstOrNull { it !is MermaidRenderResult.Rendered }} (real render still timing out after retries under load)")
            } finally {
                actor.close()
            }
        }
    }
}
