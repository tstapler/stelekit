// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — see HostDirectoryTestFixtures.kt.

/** A handle that reports "granted" permission but throws when the reconciliation walk lists its
 * contents — the exact combination `fakeHandleWithGrantedPermission()`'s doc comment warns about
 * and avoids. */
private fun fakeGrantedHandleThatThrowsOnWalk(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve('granted'); },
        requestPermission: function(opts) { return Promise.resolve('granted'); },
        values: function() { throw new Error('boom: directory unreadable'); }
    })
    """,
)

/**
 * Regression coverage for `HostDirectorySync.scope`'s `CoroutineExceptionHandler` (added
 * alongside the PNG-as-page fix). Before this fix, `reconnectHostDirectory`'s fire-and-forget
 * `scope.launch { runHostReconciliation(...) }` (the silent app-boot resume path) had no local
 * catch, and the instance's `scope` had no exception handler either — an uncaught exception there
 * (e.g. a real File System Access API directory that throws mid-walk) went to the platform
 * default handler with zero record in this app's own log surface.
 *
 * `PlatformFileSystemHostSyncDelegationTest.kt`'s `fakeHandleWithGrantedPermission()` fixture
 * doc comment already names this exact hazard ("A bare {queryPermission, requestPermission}
 * object without values() makes that background walk throw... from inside the launched
 * coroutine") but deliberately avoids triggering it (its `values()` returns an empty iterator).
 * Nobody had actually exercised the throwing path until this test — this uses a handle whose
 * `values()` throws, and asserts the exception reaches this app's own log buffer instead of
 * disappearing.
 *
 * Uses the *default* (non-`scopeOverride`) constructor deliberately — the fixture files'
 * `forTest`/`newSync`/`connectedSync`/`disconnectedSync` helpers all inject a plain
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` with no handler, which would make this
 * test pass regardless of whether HostDirectorySync's own default scope construction is correct.
 */
class HostDirectorySyncExceptionHandlerTest {

    private suspend fun awaitCondition(timeoutMs: Long = 3000, stepMs: Long = 20, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun reconnectHostDirectory_should_LogInsteadOfSilentlyDropping_When_TheBackgroundReconciliationLaunchThrows() = runTest {
        val graphId = "exc-handler-${Random.nextInt(0, Int.MAX_VALUE)}"
        // Default constructor — exercises the real production `scope`, not a test-injected one.
        val sync = HostDirectorySync(graphId = OpfsGraphSlug(graphId), cacheAccess = FakeCacheAccess())
        sync.lookupPersistedHandle = { _ -> fakeGrantedHandleThatThrowsOnWalk() to "/stelekit/$graphId" }

        val logCountBefore = LogManager.logs.value.count {
            it.tag == "HostDirectorySync" && it.level == LogLevel.ERROR
        }

        sync.reconnectHostDirectory(graphId)

        awaitCondition {
            LogManager.logs.value.any {
                it.tag == "HostDirectorySync" &&
                    it.level == LogLevel.ERROR &&
                    it.message.contains("Uncaught Throwable")
            }
        }

        val logCountAfter = LogManager.logs.value.count {
            it.tag == "HostDirectorySync" && it.level == LogLevel.ERROR
        }
        assertTrue(
            logCountAfter > logCountBefore,
            "an uncaught exception from the fire-and-forget reconciliation launch must be logged, " +
                "not silently dropped",
        )

        sync.close()
    }
}
