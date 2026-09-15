package dev.stapler.stelekit.ui.components

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

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
     * A fresh [MermaidEngineActor] pays GraalJS's one-time cold-start cost (JS bundle eval +
     * `mermaid.initialize()`) on its first `render()` call, which can exceed
     * [MERMAID_RENDER_TIMEOUT_MS] under contended CI load — a real, already-documented risk (see
     * ADR-001's Open Items), not something this test should mask by loosening the product's
     * timeout. A warm-up render (its result discarded) absorbs that cost before the two
     * timed/asserted concurrent calls, which then land on an already-initialized `Context`.
     */
    @Test
    fun render_should_returnDistinctResults_when_twoRealBlocksVisibleSimultaneously() = runTest {
        val actor = MermaidEngineActor()
        try {
            actor.render(MermaidRenderKey("graph TD; Warm-->Up", ThemeFingerprint(true, 1), 400))
            val results = listOf(
                async { actor.render(MermaidRenderKey("graph TD; X-->Y", ThemeFingerprint(true, 1), 400)) },
                async { actor.render(MermaidRenderKey("pie title P\n  \"A\" : 1", ThemeFingerprint(true, 1), 400)) },
            ).awaitAll()
            val rendered = results.map { it as? MermaidRenderResult.Rendered ?: error("expected Rendered, got $it") }
            assertEquals(2, rendered.map { it.svg }.distinct().size)
        } finally {
            actor.close()
        }
    }
}
