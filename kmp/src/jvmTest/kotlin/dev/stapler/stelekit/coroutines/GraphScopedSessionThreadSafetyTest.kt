package dev.stapler.stelekit.coroutines

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 0.1.2 AC1 / Task 0.1.3d: proves the [Mutex][kotlinx.coroutines.sync.Mutex] guard around
 * `switchTo`'s check-current/register-pending/swap critical section actually works under real,
 * multi-threaded dispatch — not just that it compiles. Lives in `jvmTest` (not `commonTest`)
 * because `newFixedThreadPoolContext` needs real OS threads; `kotlinx-coroutines-test`'s
 * `TestScope` is single-threaded on every platform and cannot expose a real-thread race even in
 * principle.
 */
class GraphScopedSessionThreadSafetyTest {

    @OptIn(ObsoleteCoroutinesApi::class)
    @Test
    fun `concurrent switchTo calls for the same id from real OS threads construct exactly one instance`() = runBlocking {
        val pool = newFixedThreadPoolContext(4, "gss-test-same-id")
        try {
            val session = GraphScopedSession<GraphId, FakeSession>()
            val constructionCount = AtomicInteger(0)
            val id = GraphId("journal-a")

            val results = (1..20).map {
                async(pool) {
                    session.switchTo(id) { scope ->
                        constructionCount.incrementAndGet()
                        FakeSession(scope)
                    }
                }
            }.awaitAll()

            assertEquals(
                1,
                constructionCount.get(),
                "exactly one FakeSession must be constructed under concurrent switchTo for the same id",
            )
            val first = results.first()
            for (result in results) {
                assertTrue(result === first, "every caller must receive the same instance")
            }
        } finally {
            pool.close()
        }
    }

    /**
     * [GraphScopedSession] holds exactly one `current` session at a time (mirroring
     * `GraphManager`, which has exactly one active graph) — it is not a per-`Id` cache. Racing
     * `switchTo(idA)`/`switchTo(idB)` on a *shared* holder therefore legitimately reconstructs
     * each id's session multiple times as the holder's `current` flips back and forth; asserting
     * "exactly one construction per id" would be asserting a guarantee this class never makes.
     * What *must* hold under that churn is the bookkeeping itself: every caller's returned
     * instance is genuinely a session constructed for the id it asked for (no cross-id mix-up),
     * and after all calls settle, no `pending` entry is ever left wedged (Task 0.1.2c/0.1.3's
     * guaranteed-completion guarantee, now exercised under real cross-id contention rather than
     * a single id).
     */
    @OptIn(ObsoleteCoroutinesApi::class)
    @Test
    fun `concurrent switchTo calls across two ids do not corrupt pending or current bookkeeping`() = runBlocking {
        val pool = newFixedThreadPoolContext(4, "gss-test-two-ids")
        try {
            val session = GraphScopedSession<GraphId, FakeSession>()
            val idA = GraphId("journal-a")
            val idB = GraphId("journal-b")
            val constructedFor = ConcurrentHashMap<FakeSession, GraphId>()

            val results = (1..20).map { i ->
                async(pool) {
                    val id = if (i % 2 == 0) idA else idB
                    id to session.switchTo(id) { scope ->
                        FakeSession(scope).also { constructedFor[it] = id }
                    }
                }
            }.awaitAll()

            for ((requestedId, instance) in results) {
                assertEquals(
                    requestedId,
                    constructedFor[instance],
                    "a caller that asked for $requestedId must never receive a session constructed for a different id",
                )
            }

            // A subsequent switchTo for either id must complete promptly (not hang forever), which
            // is only possible if every earlier call removed its own `pending` entry — a wedged
            // entry would otherwise deadlock this call awaiting a CompletableDeferred nothing
            // will ever complete.
            val afterA = session.switchTo(idA) { scope -> FakeSession(scope).also { constructedFor[it] = idA } }
            val afterB = session.switchTo(idB) { scope -> FakeSession(scope).also { constructedFor[it] = idB } }
            assertEquals(idA, constructedFor[afterA], "post-race switchTo(idA) must yield an idA session")
            assertEquals(idB, constructedFor[afterB], "post-race switchTo(idB) must yield an idB session")
        } finally {
            pool.close()
        }
    }
}
