// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * PageNameIndex rebuilds its matcher on a real background thread ([Dispatchers.Default]), behind
 * a real (non-test-dispatcher-driven) 500ms debounce. These tests construct the coordinator's
 * scope on a genuine [Dispatchers.Default] (not a `TestDispatcher`, whose virtual clock never
 * advances an unrelated, disconnected scheduler) and run the test body with `runBlocking` so
 * `delay()` here is a real wait too — that requires actual multi-threaded blocking, which wasmJs's
 * single-threaded runtime cannot support (`runBlocking` doesn't even resolve there). JVM/Android
 * are where this real-timing behavior can be verified; see `CaptureEnrichmentCoordinatorTest` in
 * commonTest for the rest of this class's test-dispatcher-driven coverage.
 */
class CaptureEnrichmentCoordinatorRealDispatcherTest {

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String, name: String, isJournal: Boolean = false) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now(),
        updatedAt = now(),
        isJournal = isJournal,
    )

    private suspend fun CaptureEnrichmentCoordinator.awaitMatcher(timeoutMs: Long = 3000) {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            if (pageNameIndex.matcher.value != null) return
            delay(20)
        }
        error("Matcher was still null after ${timeoutMs}ms — index did not build")
    }

    private fun makeCoordinator(
        scope: CoroutineScope,
        pageRepo: InMemoryPageRepository = InMemoryPageRepository(),
        topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    ) = CaptureEnrichmentCoordinator(pageRepo, scope, topicEnricher)

    @Test
    fun scan_matcherReady_returnsSuccessWithLinkedText() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin Multiplatform"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("I'm reading about Kotlin Multiplatform")
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals("I'm reading about [[Kotlin Multiplatform]]", success.result.linkedText)
            assertEquals(listOf("Kotlin Multiplatform"), success.result.matchedPageNames)
            assertTrue(success.confirmFirstNames.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scan_withinBudget_returnsSuccessBeforeTimeout() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Zettelkasten"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Reading about Zettelkasten", budgetMs = 500)
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals("Reading about [[Zettelkasten]]", success.result.linkedText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scan_shortSingleWordMatch_isConfirmFirstNotAutoLinked() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Today"))
        pageRepo.savePage(makePage("p2", "Kotlin Multiplatform"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Today I read about Kotlin Multiplatform")
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals("Today I read about [[Kotlin Multiplatform]]", success.result.linkedText)
            assertEquals(listOf("Kotlin Multiplatform"), success.result.matchedPageNames)
            assertEquals(listOf("Today"), success.confirmFirstNames)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scan_singleWordSixCharsOrMore_autoLinksUnchanged() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Zettelkasten"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Reading about Zettelkasten")
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals("Reading about [[Zettelkasten]]", success.result.linkedText)
            assertTrue(success.confirmFirstNames.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // Boundary case for MIN_AUTO_APPLY_SINGLE_WORD_LENGTH (6): the 5-char case above
    // (confirm-first) and 12-char case (auto-apply) don't exercise the ">=" floor itself — a
    // mutation flipping it to ">" (or the constant to 7) would slip through undetected without
    // this exactly-6-char case.
    @Test
    fun scan_singleWordExactlySixChars_autoLinksAtTheFloor() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin")) // exactly 6 chars
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Reading about Kotlin")
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals("Reading about [[Kotlin]]", success.result.linkedText)
            assertTrue(
                success.confirmFirstNames.isEmpty(),
                "a 6-char single-word match must auto-apply, not confirm-first",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scan_bareUrlExtraText_producesNoAutoLinksAndNoOrMinimalSuggestions() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin Multiplatform"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val text = "https://example.com/some-article"
            val outcome = coordinator.scan(text)
            val success = assertIs<ScanOutcome.Success>(outcome)
            assertEquals(text, success.result.linkedText)
            assertTrue(success.result.topicSuggestions.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // Story/pre-mortem follow-up: scan() must degrade like enhance() does — a Throwable from the
    // underlying scan work (e.g. an OutOfMemoryError under memory pressure, mirroring
    // PageNameIndex's own matcher-build guard) must not propagate and kill the caller's
    // collectLatest. Uses the coordinator's scanFn seam (defaults to the real ImportService.scan)
    // since the real matcher/ImportService call graph has no other reachable fault-injection
    // point — AhoCorasickMatcher/TrieEntry validate on construction and can't be built malformed.
    @Test
    fun scan_scanFnThrows_degradesToMatcherNotReadyInsteadOfPropagating() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin Multiplatform"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = CaptureEnrichmentCoordinator(
                pageRepo,
                scope,
                NoOpTopicEnricher(),
                scanFn = { _, _, _ -> throw IllegalStateException("boom") },
            )
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Reading about Kotlin Multiplatform")

            assertEquals(ScanOutcome.MatcherNotReady, outcome)
        } finally {
            scope.cancel()
        }
    }

    // Story 5.1.1's remaining AC (not yet exercised above): a READY matcher whose scan body is
    // delayed past budgetMs must return ScanOutcome.TimedOut, distinguishable by type from
    // MatcherNotReady above (same budgetMs = 0, but this time with pages seeded and the matcher
    // actually built). withTimeoutOrNull(timeMillis) returns null without ever invoking the block
    // when timeMillis <= 0 (kotlinx.coroutines documented behavior) — the cheapest deterministic
    // way to force scan()'s `outcome ?: ScanOutcome.TimedOut` fallback without a flaky real delay.
    @Test
    fun scan_matcherReadyButBudgetZero_returnsTimedOut() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin Multiplatform"))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val coordinator = makeCoordinator(scope, pageRepo)
            coordinator.awaitMatcher()

            val outcome = coordinator.scan("Reading about Kotlin Multiplatform", budgetMs = 0)
            assertIs<ScanOutcome.TimedOut>(outcome, "expected TimedOut, got: $outcome")
            Unit
        } finally {
            scope.cancel()
        }
    }
}
