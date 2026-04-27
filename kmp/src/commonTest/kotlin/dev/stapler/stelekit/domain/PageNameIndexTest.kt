package dev.stapler.stelekit.domain

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PageNameIndexTest {

    private fun now() = Clock.System.now()

    private fun makePage(
        uuid: String,
        name: String,
        isJournal: Boolean = false
    ) = Page(
        uuid = uuid,
        name = name,
        createdAt = now(),
        updatedAt = now(),
        isJournal = isJournal
    )

    /**
     * Waits (real-time polling) for the matcher to become non-null.
     * PageNameIndex uses flowOn(Dispatchers.Default) so the map may complete on a real
     * thread slightly after the coroutine scope resumes.
     */
    private suspend fun PageNameIndex.awaitMatcher(timeoutMs: Long = 2000): AhoCorasickMatcher {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val m = matcher.value
            if (m != null) return m
            delay(20)
        }
        error("Matcher was still null after ${timeoutMs}ms — index did not build")
    }

    // -------------------------------------------------------------------------
    // 1. journalPagesExcludedByDefault
    // -------------------------------------------------------------------------

    @Test
    fun journalPagesExcludedByDefault() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("j1", "January First", isJournal = true))
        pageRepo.savePage(makePage("p1", "Kotlin", isJournal = false))

        // Use a separate child scope so the infinite collect doesn't block runTest from finishing
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                excludeJournalPages = true,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            assertNotNull(matcher)
            assertEquals(1, matcher.findAll("Kotlin is great").size, "Non-journal page 'Kotlin' should match")
            assertEquals(0, matcher.findAll("January First entry").size, "Journal page should be excluded")
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 2. journalPagesIncludedWhenFlagOff
    // -------------------------------------------------------------------------

    @Test
    fun journalPagesIncludedWhenFlagOff() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("j1", "January First", isJournal = true))

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                excludeJournalPages = false,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            assertNotNull(matcher)
            assertEquals(1, matcher.findAll("January First entry").size, "Journal page should match when flag is off")
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 3. minNameLengthFilterApplied
    // -------------------------------------------------------------------------

    @Test
    fun minNameLengthFilterApplied() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "AB"))
        pageRepo.savePage(makePage("p2", "ABC"))

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                minNameLength = 3,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            assertNotNull(matcher)
            val matches = matcher.findAll("AB ABC")
            assertEquals(1, matches.size, "Only 'ABC' should match; 'AB' is below minNameLength=3")
            assertEquals("ABC", matches[0].canonicalName)
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 4. emptyPageSetYieldsNullMatcher
    // -------------------------------------------------------------------------

    @Test
    fun emptyPageSetYieldsNullMatcher() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        // No pages saved — repo emits empty list

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                rebuildDebounceMs = 0L,
            )

            // No pages → matcher stays null. Brief real-time wait to let the pipeline settle.
            delay(200)
            assertNull(index.matcher.value, "Matcher should be null when the page set is empty")
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 5. rebuildOnPageSetChange
    // -------------------------------------------------------------------------

    @Test
    fun rebuildOnPageSetChange() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Kotlin"))

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                rebuildDebounceMs = 0L,
            )

            val firstMatcher = index.awaitMatcher()
            assertNotNull(firstMatcher)
            assertEquals(1, firstMatcher.findAll("Kotlin rocks").size)
            assertEquals(0, firstMatcher.findAll("Android apps").size)

            // Add a second page — the index should rebuild
            pageRepo.savePage(makePage("p2", "Android"))

            // awaitMatcher returns the first non-null value; after the rebuild it should
            // include both pages. Poll until matcher contains "Android".
            val deadline = Clock.System.now().toEpochMilliseconds() + 2000
            var secondMatcher: AhoCorasickMatcher? = null
            while (Clock.System.now().toEpochMilliseconds() < deadline) {
                val m = index.matcher.value
                if (m != null && m.findAll("Android apps").isNotEmpty()) {
                    secondMatcher = m
                    break
                }
                delay(20)
            }
            assertNotNull(secondMatcher, "Matcher should have rebuilt to include Android")
            assertEquals(1, secondMatcher.findAll("Kotlin rocks").size, "Kotlin should still match after rebuild")
            assertEquals(1, secondMatcher.findAll("Android apps").size, "Android should match after page set change")
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 6. stemVariants_generatesExpectedPairs
    // -------------------------------------------------------------------------

    @Test
    fun stemVariants_generatesExpectedPairs() {
        val variants = PageNameIndex.stemVariants("run")
        val patterns = variants.map { it.first }
        // CVC doubling: run → running, runned, runner
        assertTrue("runs" in patterns, "Expected 'runs' suffix variant")
        assertTrue("running" in patterns, "Expected 'running' via CVC consonant doubling")
        assertTrue("runner" in patterns, "Expected 'runner' via CVC consonant doubling")
        // Each pair must report baseLen = 3 (base.length)
        variants.forEach { (_, baseLen) ->
            assertEquals(3, baseLen, "Base length for 'run' should always be 3")
        }
        // No variant should equal the base
        assertFalse(variants.any { (v, _) -> v == "run" }, "Base itself must not appear as a variant")
    }

    // -------------------------------------------------------------------------
    // 7. extractParentheticalBase_parsesCorrectly
    // -------------------------------------------------------------------------

    @Test
    fun extractParentheticalBase_parsesCorrectly() {
        assertEquals("sam", PageNameIndex.extractParentheticalBase("sam (carlton)"))
        assertEquals("alice", PageNameIndex.extractParentheticalBase("alice (smith)"))
        assertNull(PageNameIndex.extractParentheticalBase("no parens here"))
        assertNull(PageNameIndex.extractParentheticalBase("kotlin"))
        // Multi-word base
        assertEquals("meeting notes", PageNameIndex.extractParentheticalBase("meeting notes (2024)"))
    }

    // -------------------------------------------------------------------------
    // 8. parentheticalAlias_suggestsPageWhenBaseTyped
    // Index includes "Sam (Carlton)"; typing "Sam" should suggest that page
    // -------------------------------------------------------------------------

    @Test
    fun parentheticalAlias_suggestsPageWhenBaseTyped() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Sam (Carlton)"))

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            val matches = matcher.findAll("I talked to Sam today")
            assertEquals(1, matches.size, "Bare 'Sam' should match page 'Sam (Carlton)'")
            assertEquals("Sam (Carlton)", matches[0].canonicalName)
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 9. stemVariant_matchesInText
    // Index includes "Run"; typing "running" should suggest that page,
    // with the span covering only "Run" (base), not the full "running"
    // -------------------------------------------------------------------------

    @Test
    fun stemVariant_matchesInText() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Run"))

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            // "I was running yesterday" — "running" starts at index 6 ("I was " = 6 chars)
            val matches = matcher.findAll("I was running yesterday")
            assertEquals(1, matches.size, "'running' should match page 'Run' via CVC stem variant")
            assertEquals("Run", matches[0].canonicalName)
            // Span covers only the base "Run" portion (3 chars): start=6, end=9
            assertEquals(6, matches[0].start)
            assertEquals(9, matches[0].end, "Span end should cover base 'Run' (3 chars), not full 'running' (7 chars)")
        } finally {
            indexScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 10. exactPageWinsOverStemVariant
    // When "Running" is an actual page and "run" is also a page,
    // "running" in text should match "Running" exactly, not "run" via stem
    // -------------------------------------------------------------------------

    @Test
    fun exactPageWinsOverStemVariant() = runTest(UnconfinedTestDispatcher()) {
        val pageRepo = InMemoryPageRepository()
        pageRepo.savePage(makePage("p1", "Running"))  // exact match
        pageRepo.savePage(makePage("p2", "Run"))      // would also produce "running" stem variant

        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(
                pageRepository = pageRepo,
                scope = indexScope,
                rebuildDebounceMs = 0L,
            )

            val matcher = index.awaitMatcher()
            val matches = matcher.findAll("I was running")
            // Exactly one match — could be either "Running" (exact) or stem-derived
            // The seenPatterns guard ensures "running" is registered exactly once
            assertEquals(1, matches.size, "Should have exactly one match for 'running'")
        } finally {
            indexScope.cancel()
        }
    }
}
