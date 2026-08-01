// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionViewModelTest {

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String, name: String) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now(),
        updatedAt = now(),
    )

    private suspend fun PageNameIndex.awaitMatcher(timeoutMs: Long = 2000): AhoCorasickMatcher {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val m = matcher.value
            if (m != null) return m
            delay(20)
        }
        error("Matcher still null after ${timeoutMs}ms")
    }

    /**
     * Polls [TagSuggestionViewModel.state] until it satisfies [predicate] or times out.
     * Necessary because [TagSuggestionViewModel] owns its own [CoroutineScope] with
     * [kotlinx.coroutines.Dispatchers.Default], which [kotlinx.coroutines.test.advanceUntilIdle]
     * cannot control. When the VM is constructed with a `dispatcher` that shares the caller's
     * `testScheduler`, this same delay-based polling also works correctly under virtual time —
     * `runTest` auto-advances the shared scheduler while the test coroutine is suspended.
     */
    private suspend fun TagSuggestionViewModel.awaitState(
        timeoutMs: Long = 5000,
        predicate: (TagSuggestionState) -> Boolean,
    ): TagSuggestionState {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val s = state.value
            if (predicate(s)) return s
            delay(20)
        }
        error("State ${state.value} never satisfied predicate within ${timeoutMs}ms")
    }

    private suspend fun TagSuggestionViewModel.awaitScanState(
        timeoutMs: Long = 5000,
        predicate: (BulkScanState) -> Boolean,
    ): BulkScanState {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val s = scanState.value
            if (predicate(s)) return s
            delay(20)
        }
        error("Scan state ${scanState.value} never satisfied predicate within ${timeoutMs}ms")
    }

    private fun makeIdleEngine(indexScope: CoroutineScope): TagSuggestionEngine {
        val repo = InMemoryPageRepository()
        val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
        return TagSuggestionEngine(index, llmTagProvider = null)
    }

    /** Builds a [TagSuggestionEngine] backed by a real [LlmTagProvider]/[PageNameIndex] pair, so
     * [TagSuggestionEngine.llmSuggest] exercises the real token-overlap filtering + format() call
     * path rather than a hand-rolled fake. [vocabulary] must share at least one token with the
     * block content used in the test, or [formatter] is never invoked (empty-vocabulary fast path). */
    private fun makeEngine(
        indexScope: CoroutineScope,
        vocabulary: List<String>,
        formatter: LlmFormatterProvider,
        checkAvailability: (suspend () -> LlmProviderAvailability)? = null,
    ): TagSuggestionEngine {
        val repo = InMemoryPageRepository()
        val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
        val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 5)
        return TagSuggestionEngine(
            pageNameIndex = index,
            llmTagProvider = llmProvider,
            vocabularyProvider = { vocabulary },
            checkAvailability = checkAvailability,
        )
    }

    // ─── initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            assertIs<TagSuggestionState.Idle>(vm.state.value)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── requestSuggestions ───────────────────────────────────────────────────

    @Test
    fun `requestSuggestions transitions to Ready`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-1", "Some content")
            val ready = vm.awaitState { it is TagSuggestionState.Ready }
            assertIs<TagSuggestionState.Ready>(ready)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `Ready state contains correct blockUuid`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-42", "Some content")
            val state = vm.awaitState { it is TagSuggestionState.Ready }
            assertIs<TagSuggestionState.Ready>(state)
            assertTrue(state.blockUuid == "block-42")
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── dismiss ─────────────────────────────────────────────────────────────

    @Test
    fun `dismiss resets state to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-1", "Some content")
            vm.awaitState { it is TagSuggestionState.Ready }

            vm.dismiss()
            assertIs<TagSuggestionState.Idle>(vm.state.value)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── LLM tier ─────────────────────────────────────────────────────────────

    @Test
    fun `LLM suggestions appear in Ready state when provider returns results`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = InMemoryPageRepository()
            repo.savePage(makePage("1", "Kotlin"))
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
                index.awaitMatcher()
                val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success("Kotlin", false) }
                val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 5)
                val engine = TagSuggestionEngine(
                    pageNameIndex = index,
                    llmTagProvider = llmProvider,
                    vocabularyProvider = { listOf("Kotlin") },
                )
                val vm = TagSuggestionViewModel(engine)
                vm.requestSuggestions("block-1", "I love Kotlin")
                // Wait until LLM suggestions are populated
                val state = vm.awaitState {
                    it is TagSuggestionState.Ready && it.llmSuggestions.isNotEmpty()
                }
                assertIs<TagSuggestionState.Ready>(state)
                assertTrue(state.llmSuggestions.isNotEmpty(), "Expected LLM suggestions but got none")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    // ─── Story 4.1: runLlmSuggest background polling (FR-0/FR-1) ──────────────

    @Test
    fun `runLlmSuggest polls checkAvailability in the background after the initial Downloading caption`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        try {
            var checkAvailabilityCalls = 0
            var formatCalls = 0
            val formatter = LlmFormatterProvider { _, _ ->
                formatCalls++
                if (formatCalls == 1) {
                    LlmResult.Failure.OnDeviceUnavailable(
                        "Downloading on-device model — this may take a few minutes",
                        retryable = true,
                    )
                } else {
                    LlmResult.Success("Q3-Planning")
                }
            }
            val engine = makeEngine(
                indexScope,
                vocabulary = listOf("Q3-Planning"),
                formatter = formatter,
                checkAvailability = {
                    checkAvailabilityCalls++
                    if (checkAvailabilityCalls >= 3) LlmProviderAvailability.Available
                    else LlmProviderAvailability.Preparing("downloading")
                },
            )
            val vm = TagSuggestionViewModel(
                engine,
                dispatcher = testDispatcher,
                pollDeadlineMs = 1_000L,
                pollIntervalMs = 10L,
            )

            vm.requestSuggestions("block-abc123", "Meeting notes about Q3 planning")
            advanceUntilIdle()

            val finalState = vm.state.value
            assertIs<TagSuggestionState.Ready>(finalState)
            assertEquals(LlmSuggestionStatus.Resolved, finalState.llmStatus)
            assertTrue(
                checkAvailabilityCalls >= 3,
                "expected the poll loop to keep checking availability in the background, without a manual retrigger",
            )
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `requestSuggestions auto re-runs and resolves to real results once Available is observed, no manual retrigger`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val indexScope = CoroutineScope(testDispatcher)
            try {
                var formatCalls = 0
                var checkAvailabilityCalls = 0
                val formatter = LlmFormatterProvider { _, _ ->
                    formatCalls++
                    if (formatCalls == 1) {
                        LlmResult.Failure.OnDeviceUnavailable(
                            "Downloading on-device model — this may take a few minutes",
                            retryable = true,
                        )
                    } else {
                        LlmResult.Success("Q3-Planning")
                    }
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Q3-Planning"),
                    formatter = formatter,
                    checkAvailability = {
                        checkAvailabilityCalls++
                        if (checkAvailabilityCalls >= 3) LlmProviderAvailability.Available
                        else LlmProviderAvailability.Preparing("downloading")
                    },
                )
                val vm = TagSuggestionViewModel(
                    engine,
                    dispatcher = testDispatcher,
                    pollDeadlineMs = 1_000L,
                    pollIntervalMs = 10L,
                )

                vm.requestSuggestions("block-abc123", "Meeting notes about Q3 planning")
                advanceUntilIdle()

                val finalState = vm.state.value
                assertIs<TagSuggestionState.Ready>(finalState)
                assertEquals(LlmSuggestionStatus.Resolved, finalState.llmStatus)
                assertTrue(finalState.llmSuggestions.any { it.term == "Q3-Planning" })
                assertEquals(2, formatCalls, "auto re-run must call the LLM exactly twice — no manual retrigger needed")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    // ─── Story 4.2: requestSuggestions() rewrite ───────────────────────────────

    @Test
    fun `llmStatus transitions Pending(null) to Pending(reason) to Pending(escalated) to Stalled across a full poll deadline`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val indexScope = CoroutineScope(testDispatcher)
            try {
                val reason = "Downloading on-device model — this may take a few minutes"
                val formatter = LlmFormatterProvider { _, _ ->
                    LlmResult.Failure.OnDeviceUnavailable(reason, retryable = true)
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
                )
                val vm = TagSuggestionViewModel(
                    engine,
                    dispatcher = testDispatcher,
                    pollDeadlineMs = 1_000L,
                    pollIntervalMs = 100L,
                    pollEscalationThresholdMs = 400L,
                )

                // Collect on an Unconfined-flavored dispatcher (still sharing testScheduler for
                // any virtual delays) so each StateFlow emission is observed synchronously as it
                // happens, rather than via a separately-queued dispatch — a StandardTestDispatcher
                // collector can miss the transient Pending(null) emission because it's conflated
                // away by the very next write before the collector gets a chance to run (the
                // producer coroutine doesn't actually suspend between those two writes here).
                val statuses = mutableListOf<LlmSuggestionStatus>()
                val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.state.collect { s ->
                        if (s is TagSuggestionState.Ready && (statuses.isEmpty() || statuses.last() != s.llmStatus)) {
                            statuses += s.llmStatus
                        }
                    }
                }

                vm.requestSuggestions("block-abc123", "Learning Kotlin today")
                advanceUntilIdle()
                collectJob.cancel()

                assertEquals(
                    listOf(
                        LlmSuggestionStatus.Pending(null),
                        LlmSuggestionStatus.Pending(reason),
                        LlmSuggestionStatus.Pending(TagAvailabilityPoller.ESCALATED_WAIT_CAPTION),
                        LlmSuggestionStatus.Stalled(retryable = true),
                    ),
                    statuses,
                )
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    @Test
    fun `retryLastRequest re-invokes requestSuggestions with stored args and restarts from Pending`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var formatCalls = 0
                val formatter = LlmFormatterProvider { _, _ ->
                    formatCalls++
                    if (formatCalls == 1) {
                        LlmResult.Failure.OnDeviceUnavailable(
                            "Downloading on-device model — this may take a few minutes",
                            retryable = true,
                        )
                    } else {
                        LlmResult.Success("Kotlin")
                    }
                }
                // No checkAvailability probe wired — runLlmSuggest returns the first retryable
                // failure directly, with no poll loop ever running. Since it never reaches
                // TagAvailabilityPoller's own STALLED_REASON terminal, it maps to a retryable
                // Failed (preserving the real SDK message and still offering a Retry button) —
                // NOT Stalled, which is reserved specifically for the poll loop's own deadline
                // signal (see the ifLeft handler in requestSuggestions()). retryLastRequest
                // (not the poll loop) is what drives the second attempt here either way.
                val engine = makeEngine(indexScope, vocabulary = listOf("Kotlin"), formatter = formatter)
                val vm = TagSuggestionViewModel(engine)

                vm.requestSuggestions("block-retry1", "Learning Kotlin today")
                val stalled = vm.awaitState {
                    it is TagSuggestionState.Ready &&
                        (it.llmStatus as? LlmSuggestionStatus.Failed)?.retryable == true
                }
                assertIs<TagSuggestionState.Ready>(stalled)
                assertEquals("block-retry1", stalled.blockUuid)
                assertEquals(
                    "Downloading on-device model — this may take a few minutes",
                    (stalled.llmStatus as LlmSuggestionStatus.Failed).message,
                )

                vm.retryLastRequest()
                val resolved = vm.awaitState { it is TagSuggestionState.Ready && it.llmStatus == LlmSuggestionStatus.Resolved }
                assertIs<TagSuggestionState.Ready>(resolved)
                assertEquals("block-retry1", resolved.blockUuid)
                assertTrue(resolved.llmSuggestions.isNotEmpty())
                assertEquals(2, formatCalls, "retry must re-invoke the LLM call, not merely replay cached state")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    @Test
    fun `requestSuggestions resolves with zero poll calls when checkAvailability reports Available immediately`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var checkAvailabilityCalls = 0
                val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success("Kotlin") }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Available },
                )
                val vm = TagSuggestionViewModel(engine)

                vm.requestSuggestions("block-fast1", "Learning Kotlin today")
                val state = vm.awaitState { it is TagSuggestionState.Ready && it.llmStatus == LlmSuggestionStatus.Resolved }
                assertIs<TagSuggestionState.Ready>(state)
                assertTrue(state.llmSuggestions.isNotEmpty())
                assertEquals(0, checkAvailabilityCalls, "fast path must never touch the availability probe")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    @Test
    fun `requestSuggestions does not start a poll loop when the first failure is non-retryable`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var checkAvailabilityCalls = 0
                val formatter = LlmFormatterProvider { _, _ ->
                    LlmResult.Failure.OnDeviceUnavailable("On-device AI is not supported on this device", retryable = false)
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Preparing("n/a") },
                )
                val vm = TagSuggestionViewModel(engine)

                vm.requestSuggestions("block-unsupported1", "Learning Kotlin today")
                val state = vm.awaitState { it is TagSuggestionState.Ready && it.llmStatus is LlmSuggestionStatus.Failed }
                assertIs<TagSuggestionState.Ready>(state)
                val failed = state.llmStatus as LlmSuggestionStatus.Failed
                assertEquals("On-device AI is not supported on this device", failed.message)
                assertFalse(failed.retryable)
                assertEquals(0, checkAvailabilityCalls, "genuinely-unsupported path must never start a poll loop")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    // ─── Story 4.3: scanEntries() allowPolling=false (FR-7/AC7) ───────────────

    @Test
    fun `scanEntries fails fast per entry without polling when allowPolling is false`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var checkAvailabilityCalls = 0
                var formatCalls = 0
                val formatter = LlmFormatterProvider { _, _ ->
                    formatCalls++
                    if (formatCalls == 2) {
                        LlmResult.Failure.OnDeviceUnavailable(
                            "Downloading on-device model — this may take a few minutes",
                            retryable = true,
                        )
                    } else {
                        LlmResult.Success("Kotlin")
                    }
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Preparing("still downloading") },
                )
                val vm = TagSuggestionViewModel(engine)
                val entries = listOf(
                    JournalScanEntry("page-1", "block-1", "content1", "Learning Kotlin", emptySet(), "graph-1"),
                    JournalScanEntry("page-2", "block-2", "content2", "Learning Kotlin", emptySet(), "graph-1"),
                    JournalScanEntry("page-3", "block-3", "content3", "Learning Kotlin", emptySet(), "graph-1"),
                )

                vm.scanEntries(entries)
                val complete = vm.awaitScanState { it is BulkScanState.Complete }
                assertIs<BulkScanState.Complete>(complete)
                assertEquals(3, formatCalls, "all three entries must have been attempted")
                assertEquals(0, checkAvailabilityCalls, "allowPolling=false must never touch the availability probe")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    // ─── Story 4.4: stale-block leak + AC5 lifecycle ───────────────────────────

    @Test
    fun `poll loop for a stale block does not write into a newly active block's cache`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var checkAvailabilityCalls = 0
                // block-B's content is distinguished so it resolves on the FIRST attempt (no
                // polling of its own). This isolates checkAvailability() call growth during the
                // real-time wait below to ONLY a leaked, should-be-cancelled block-A poll job —
                // if block-B also polled, its own legitimate ticks would be indistinguishable
                // from a leaked block-A tick and the test could not discriminate the two.
                val formatter = LlmFormatterProvider { blockContent, _ ->
                    if (blockContent.contains("block-B-marker")) {
                        LlmResult.Success("Kotlin")
                    } else {
                        LlmResult.Failure.OnDeviceUnavailable(
                            "Downloading on-device model — this may take a few minutes",
                            retryable = true,
                        )
                    }
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Preparing("still downloading") },
                )
                // pollIntervalMs is overridden short (50ms) so the genuine real-time waits below
                // (well under the real 4000ms production interval) are long enough to actually
                // engage the poll loop. Without this override the bare-delay version of this test
                // used the real 4000ms DEFAULT_POLL_INTERVAL_MS and could never observe a leaked
                // tick regardless of whether the stale block-A job was actually cancelled.
                val vm = TagSuggestionViewModel(engine, pollIntervalMs = 50L)

                // Given: block-A stuck at Preparing forever (checkAvailability never resolves).
                vm.requestSuggestions("block-A", "Learning Kotlin today")
                vm.awaitState {
                    it is TagSuggestionState.Ready && it.blockUuid == "block-A" &&
                        it.llmStatus is LlmSuggestionStatus.Pending &&
                        (it.llmStatus as LlmSuggestionStatus.Pending).caption != null
                }

                // When: user switches to block-B before block-A's poll loop resolves or hits deadline.
                // This test constructs the VM with real Dispatchers.Default (matching production,
                // deliberately — an earlier attempt to run it on a shared virtual-time test
                // scheduler introduced its own, worse timing complexity, since block-A's endless
                // 50ms poll loop competes for scheduler cycles even though it never resolves). The
                // timeout here is a generous, one-time safety margin — not a tight bound — chosen
                // to comfortably absorb GitHub Actions CI's real thread-pool contention (observed
                // repeatedly landing the underlying, near-instantaneous transition at 5-15+ real
                // seconds under CI load vs. <2s locally in isolation every time it's been checked).
                vm.requestSuggestions("block-B", "Learning Kotlin today, block-B-marker")
                vm.awaitState(timeoutMs = 60000) {
                    it is TagSuggestionState.Ready && it.blockUuid == "block-B" &&
                        it.llmStatus == LlmSuggestionStatus.Resolved
                }
                val callsAfterSwitch = checkAvailabilityCalls

                // Give the (should-be-cancelled) block-A poll job a chance to misbehave if it
                // wasn't actually cancelled — well short of the real 4000ms production interval,
                // but several multiples of the 50ms pollIntervalMs override above. Genuine
                // wall-clock wait (Dispatchers.Default, not the runTest virtual scheduler) — a
                // bare delay() here would be virtualized to near-zero real time and could never
                // observe a leaked tick.
                withContext(Dispatchers.Default) { delay(200) }
                assertEquals(
                    callsAfterSwitch,
                    checkAvailabilityCalls,
                    "a leaked stale block-A poll job kept calling checkAvailability() after switching to " +
                        "block-B, which resolves on its first attempt and never polls on its own",
                )

                // Then: re-requesting block-A starts a *fresh* run (Pending(null), cold start) —
                // it could NOT have started fresh if the old, supposedly-cancelled job had
                // silently kept running and left a Stalled/Resolved result in the cache.
                vm.requestSuggestions("block-A", "Learning Kotlin today")
                val blockAAgain = vm.awaitState(timeoutMs = 60000) {
                    it is TagSuggestionState.Ready && it.blockUuid == "block-A" &&
                        it.llmStatus == LlmSuggestionStatus.Pending(null)
                }
                assertIs<TagSuggestionState.Ready>(blockAAgain)
                assertEquals("block-A", blockAAgain.blockUuid)
                assertEquals(LlmSuggestionStatus.Pending(null), blockAAgain.llmStatus)
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }

    @Test
    fun `close cancels the poll loop and no further checkAvailability calls occur`() =
        runTest(UnconfinedTestDispatcher()) {
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                var checkAvailabilityCalls = 0
                val formatter = LlmFormatterProvider { _, _ ->
                    LlmResult.Failure.OnDeviceUnavailable(
                        "Downloading on-device model — this may take a few minutes",
                        retryable = true,
                    )
                }
                val engine = makeEngine(
                    indexScope,
                    vocabulary = listOf("Kotlin"),
                    formatter = formatter,
                    checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Preparing("still downloading") },
                )
                // pollIntervalMs is overridden short (50ms) so that a genuine real-time wait
                // below (well under the real 4000ms production interval) is still long enough
                // to observe multiple poll ticks if close() failed to cancel the loop — without
                // this override, the bare-delay version of this test used the real 4000ms
                // DEFAULT_POLL_INTERVAL_MS and could never observe a tick regardless of whether
                // close() actually cancelled anything.
                val vm = TagSuggestionViewModel(engine, pollIntervalMs = 50L)

                vm.requestSuggestions("block-abc123", "Learning Kotlin today")
                vm.awaitState {
                    it is TagSuggestionState.Ready && it.llmStatus is LlmSuggestionStatus.Pending &&
                        (it.llmStatus as LlmSuggestionStatus.Pending).caption != null
                }

                vm.close()
                val countAtClose = checkAvailabilityCalls
                // Genuine wall-clock wait (Dispatchers.Default, not the runTest virtual
                // scheduler) — a bare delay() here would be virtualized to near-zero real time
                // and could never actually observe a leaked poll tick.
                withContext(Dispatchers.Default) { delay(200) }
                assertEquals(countAtClose, checkAvailabilityCalls, "close() must stop the poll loop, not merely detach from it")
            } finally {
                indexScope.cancel()
            }
        }

    @Test
    fun `suggestionJob becomes inactive on its own once the poll deadline elapses`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        val formatter = LlmFormatterProvider { _, _ ->
            LlmResult.Failure.OnDeviceUnavailable(
                "Downloading on-device model — this may take a few minutes",
                retryable = true,
            )
        }
        val engine = makeEngine(
            indexScope,
            vocabulary = listOf("Kotlin"),
            formatter = formatter,
            checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
        )
        val vm = TagSuggestionViewModel(
            engine,
            dispatcher = testDispatcher,
            pollDeadlineMs = 200L,
            pollIntervalMs = 50L,
        )
        vm.requestSuggestions("block-abc123", "Learning Kotlin today")
        advanceUntilIdle()

        assertFalse(vm.isSuggestionJobActiveForTest)
        vm.close()
        indexScope.cancel()
    }

    // ─── Story 4.5: format() not re-triggered per poll tick (pitfall #2) ──────

    @Test
    fun `format is called at most twice across a full poll cycle, never once per tick`() = runTest {
        var formatCalls = 0
        var checkAvailabilityCalls = 0
        val formatter = LlmFormatterProvider { _, _ ->
            formatCalls++
            if (formatCalls == 1) {
                LlmResult.Failure.OnDeviceUnavailable(
                    "Downloading on-device model — this may take a few minutes",
                    retryable = true,
                )
            } else {
                LlmResult.Success("Kotlin")
            }
        }
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        val engine = makeEngine(
            indexScope,
            vocabulary = listOf("Kotlin"),
            formatter = formatter,
            checkAvailability = {
                checkAvailabilityCalls++
                if (checkAvailabilityCalls >= 5) LlmProviderAvailability.Available
                else LlmProviderAvailability.Preparing("downloading")
            },
        )
        val vm = TagSuggestionViewModel(
            engine,
            dispatcher = testDispatcher,
            pollDeadlineMs = 1_000L,
            pollIntervalMs = 10L,
        )
        vm.requestSuggestions("block-abc123", "Learning Kotlin")
        advanceUntilIdle()

        assertEquals(2, formatCalls, "format() must be called exactly once for the initial attempt and once after Available resolves — never per poll tick")
        assertEquals(5, checkAvailabilityCalls, "checkAvailability() carries the per-tick polling load, not format()")
        vm.close()
        indexScope.cancel()
    }

    // ─── Story 4.6: elapsed-time persistence (pre-mortem P1 #1/#2) ────────────

    /**
     * NOTE on why this test uses a small *real* [delay] (via [Dispatchers.Default], not the
     * shared [testScheduler]) instead of purely virtual-time advancement: [downloadFirstObservedAtMs]
     * is folded into [TagAvailabilityPoller.pollUntilAvailable]'s `startedAtOverride` parameter,
     * and that function (Epic 3, already committed, not modifiable here) reconciles it with a
     * single real `kotlin.time.Clock.System.now()` read — by design, so *production* behavior
     * (a block-switch that genuinely takes real wall-clock time) resumes the elapsed-time budget
     * correctly. `kotlinx.coroutines.test`'s virtual clock has no way to influence `Clock.System`,
     * so a block-switch-and-return that only advances the *virtual* scheduler (no real time
     * elapsed) cannot exercise this resumption path at all — the second `runLlmSuggest` call
     * would see `elapsedSoFar` computed from an unchanged wall clock, i.e. effectively zero,
     * indistinguishable from a fresh start. A tiny (milliseconds-scale) genuine sleep here is the
     * only way to honestly exercise the persisted-elapsed-time contract; it is not a violation of
     * NFR-3 (which targets the ~120s/~20s *production-scale* waits, not a deliberate few hundred
     * milliseconds standing in for "user was gone from this block for a bit").
     */
    @Test
    fun `poll elapsed time survives a block-switch-and-return, escalating immediately and reaching Stalled early`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        var checkAvailabilityCalls = 0
        val formatter = LlmFormatterProvider { _, _ ->
            LlmResult.Failure.OnDeviceUnavailable(
                "Downloading on-device model — this may take a few minutes",
                retryable = true,
            )
        }
        val engine = makeEngine(
            indexScope,
            vocabulary = listOf("Content"),
            formatter = formatter,
            checkAvailability = { checkAvailabilityCalls++; LlmProviderAvailability.Preparing("still downloading") },
        )
        val realSleepMs = 250L
        val escalationThresholdMs = 100L // real sleep (250ms) comfortably exceeds this
        val deadlineMs = 5_000L // comfortably exceeds the real sleep, so we don't prematurely stall
        val vm = TagSuggestionViewModel(
            engine,
            dispatcher = testDispatcher,
            pollDeadlineMs = deadlineMs,
            pollIntervalMs = 50L,
            pollEscalationThresholdMs = escalationThresholdMs,
        )

        // Block A: first attempt fails retryable and the poll loop starts (this is when
        // downloadFirstObservedAtMs is set, to a real Clock.System.now() timestamp).
        vm.requestSuggestions("block-A", "content A")
        vm.awaitState {
            it is TagSuggestionState.Ready && it.blockUuid == "block-A" &&
                it.llmStatus is LlmSuggestionStatus.Pending && (it.llmStatus as LlmSuggestionStatus.Pending).caption != null
        }

        // A genuine wall-clock gap standing in for "user switched away from this block for a
        // bit" — see the KDoc above for why this must be real time, not virtual.
        withContext(Dispatchers.Default) { delay(realSleepMs) }

        vm.requestSuggestions("block-B", "content B")
        vm.awaitState { it is TagSuggestionState.Ready && it.blockUuid == "block-B" }
        val callsBeforeReturnToA = checkAvailabilityCalls

        // Switch back to block A.
        vm.requestSuggestions("block-A", "content A")

        // Then: block A's relaunch shows the ESCALATED caption immediately — never the
        // cold-start caption again — proving downloadFirstObservedAtMs was not reset.
        vm.awaitState {
            it is TagSuggestionState.Ready &&
                it.blockUuid == "block-A" &&
                it.llmStatus == LlmSuggestionStatus.Pending(TagAvailabilityPoller.ESCALATED_WAIT_CAPTION)
        }
        // The escalated caption comes directly from runLlmSuggest's own initialCaption selection
        // (elapsedSoFar already exceeds the threshold on entry) — it must NOT require climbing
        // fresh ticks from 0ms up to escalationThresholdMs inside pollUntilAvailable first.
        assertTrue(
            checkAvailabilityCalls - callsBeforeReturnToA <= 1,
            "escalated caption must appear without a fresh climb from 0ms to the escalation threshold",
        )

        advanceUntilIdle()
        val finalState = vm.state.value as TagSuggestionState.Ready
        assertEquals("block-A", finalState.blockUuid)
        assertEquals(LlmSuggestionStatus.Stalled(retryable = true), finalState.llmStatus)

        vm.close()
        indexScope.cancel()
    }

    /**
     * See the KDoc on the previous test for why this uses a small *real* [delay] rather than
     * pure virtual-time advancement: [TagAvailabilityPoller.pollUntilAvailable]'s
     * `startedAtOverride` reconciliation is tied to a real `Clock.System.now()` read (Epic 3,
     * unmodifiable here), so "a retry that happens after the deadline has genuinely elapsed"
     * can only be exercised with genuine wall-clock time — a purely virtual deadline crossing
     * (via `advanceUntilIdle()`/`advanceTimeBy`) leaves the real clock unchanged, which would
     * make this regression test pass vacuously (by *also* resolving via a fresh full poll cycle,
     * not because elapsed time was actually preserved) instead of proving the fix.
     */
    @Test
    fun `retryLastRequest after Stalled reaches Stalled again immediately, not after a fresh deadline`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        var checkAvailabilityCalls = 0
        val formatter = LlmFormatterProvider { _, _ ->
            LlmResult.Failure.OnDeviceUnavailable(
                "Downloading on-device model — this may take a few minutes",
                retryable = true,
            )
        }
        val engine = makeEngine(
            indexScope,
            vocabulary = listOf("Content"),
            formatter = formatter,
            checkAvailability = {
                checkAvailabilityCalls++
                LlmProviderAvailability.Preparing("still downloading")
            },
        )
        val deadlineMs = 100L
        val realSleepMs = 250L // comfortably exceeds deadlineMs, standing in for "user waited, then tapped retry"
        val vm = TagSuggestionViewModel(
            engine,
            dispatcher = testDispatcher,
            pollDeadlineMs = deadlineMs,
            pollIntervalMs = 20L,
            pollEscalationThresholdMs = 40L,
        )

        vm.requestSuggestions("block-abc123", "content abc")
        advanceUntilIdle() // runs the full (virtual) 100ms poll cycle to its own Stalled deadline
        assertEquals(
            LlmSuggestionStatus.Stalled(retryable = true),
            (vm.state.value as TagSuggestionState.Ready).llmStatus,
        )
        val callsAtFirstStall = checkAvailabilityCalls

        // Real wall-clock gap — see KDoc above.
        withContext(Dispatchers.Default) { delay(realSleepMs) }

        vm.retryLastRequest()
        advanceUntilIdle()

        // Then: back to Stalled again, but with (at most) 1 additional checkAvailability() call
        // — not a fresh multi-tick poll cycle.
        assertEquals(
            LlmSuggestionStatus.Stalled(retryable = true),
            (vm.state.value as TagSuggestionState.Ready).llmStatus,
        )
        assertTrue(
            checkAvailabilityCalls - callsAtFirstStall <= 1,
            "retry after a genuine Stalled must not restart a fresh multi-tick poll cycle",
        )

        vm.close()
        indexScope.cancel()
    }

    // ─── DomainError.NetworkError.Timeout → Failed(retryable=true) pipeline ───

    /**
     * The `err is DomainError.NetworkError.Timeout -> Failed(retryable = true)` branch in
     * requestSuggestions()'s ifLeft handler previously had no test proving the ViewModel/engine
     * pipeline actually PRODUCES this state from a real timeout — existing UI tests only verify
     * rendering of a hand-constructed Failed(retryable=true) state. This drives a genuine
     * [LlmTagProvider] timeout (via a formatter that suspends past the provider's configured
     * timeout) end to end through [TagSuggestionEngine] and [TagSuggestionViewModel].
     */
    @Test
    fun `a genuine LLM timeout surfaces through the ViewModel as a retryable Failed status`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val indexScope = CoroutineScope(testDispatcher)
        try {
            val repo = InMemoryPageRepository()
            repo.savePage(makePage("1", "Kotlin"))
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            // Suspends well past LlmTagProvider's 1-second timeout below. Under the shared
            // testScheduler this is virtual time, so the test resolves instantly.
            val formatter = LlmFormatterProvider { _, _ ->
                delay(10_000)
                LlmResult.Success("Kotlin")
            }
            val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 1)
            val engine = TagSuggestionEngine(
                pageNameIndex = index,
                llmTagProvider = llmProvider,
                vocabularyProvider = { listOf("Kotlin") },
            )
            val vm = TagSuggestionViewModel(engine, dispatcher = testDispatcher)

            vm.requestSuggestions("block-1", "I love Kotlin")
            advanceUntilIdle()

            val state = vm.state.value
            assertIs<TagSuggestionState.Ready>(state)
            val status = state.llmStatus
            assertIs<LlmSuggestionStatus.Failed>(status)
            assertTrue(status.retryable, "a real Timeout must surface as retryable, not the non-retryable default")
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }
}
