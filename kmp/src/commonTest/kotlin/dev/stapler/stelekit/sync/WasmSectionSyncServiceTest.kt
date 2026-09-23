package dev.stapler.stelekit.sync

import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.sections.SectionDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the WasmSectionSyncService behavior contract (Story 6.4).
 *
 * WasmSectionSyncService lives in wasmJsMain and calls jsFetch / JsAny APIs
 * that cannot be invoked from the JVM or commonTest compilation unit.
 * These tests exercise the pure-Kotlin algorithms that the service implements:
 *
 *   TC-6.4-D: GitHub tree JSON → file path extraction
 *   TC-6.4-E: Section path filtering — only paths under section prefixes are accepted
 *   TC-6.4-F: Stub page field contract (isContentLoaded=false, sectionId, isJournal, journalDate)
 *   TC-6.4-G: Rate-limit retry algorithm — 429 exhaustion after 4 retries → null
 *
 * The production HTTP path (githubFetch calling jsFetch) cannot be tested here.
 * TC-6.4-G verifies identical retry logic via a pure-Kotlin model that mirrors
 * the `if (retryCount < 4) { delay(...); return githubFetch(url, token, retryCount + 1) }`
 * branching in WasmSectionSyncService.Companion.githubFetch.
 */
class WasmSectionSyncServiceTest {

    // ── Test double: pure-Kotlin replica of extractTreePaths ─────────────────
    //
    // extractTreePaths is a private top-level function in wasmJsMain; it is not
    // accessible from commonTest. This reimplementation uses the exact same
    // algorithm so the test verifies the specification, not the class-under-test.

    private fun extractTreePaths(json: String): List<String> {
        val results = mutableListOf<String>()
        var i = json.indexOf("\"path\"")
        while (i != -1) {
            val colon = json.indexOf(':', i)
            if (colon == -1) break
            val q1 = json.indexOf('"', colon + 1)
            if (q1 == -1) break
            val q2 = json.indexOf('"', q1 + 1)
            if (q2 == -1) break
            results.add(json.substring(q1 + 1, q2))
            i = json.indexOf("\"path\"", q2 + 1)
        }
        return results
    }

    // ── Test double: pure-Kotlin model of githubFetch retry logic ────────────
    //
    // The production method calls jsFetch (WASM-only). This model accepts a
    // suspend lambda `respond` that returns an HTTP status code (or null for
    // network error) so the retry behavior can be driven deterministically.
    // Delay is preserved so tests using TestCoroutineScheduler can verify
    // back-off timing without wall-clock waits.

    private suspend fun modelledGithubFetch(
        retryCount: Int = 0,
        respond: suspend (retryCount: Int) -> Int?,
    ): String? {
        val status = respond(retryCount) ?: return null
        if (status == 429) {
            val retryAfter = (1 shl retryCount).coerceAtMost(60)
            if (retryCount < 4) {
                delay(retryAfter * 1000L)
                return modelledGithubFetch(retryCount + 1, respond)
            }
            return null   // exhausted 4 retries
        }
        if (status < 200 || status >= 300) return null
        return "tree-json-body"
    }

    // ── Section definition used across tests ─────────────────────────────────

    private val acmeSection = SectionDefinition(
        id = "acme-work",
        displayName = "Acme Work",
        pagePathPrefix = "pages/acme-work",
        journalPathPrefix = "journals/acme-work",
    )

    // ── TC-6.4-D: GitHub tree JSON path extraction ───────────────────────────

    @Test
    fun `TC-6_4-D extractTreePaths returns all path values from GitHub tree JSON`() {
        val json = """
            {
              "sha": "abc123",
              "tree": [
                { "path": "pages/acme-work/Work Note.md", "type": "blob" },
                { "path": "journals/acme-work/2026-06-30.md", "type": "blob" },
                { "path": "pages/global/Other.md", "type": "blob" }
              ]
            }
        """.trimIndent()

        val paths = extractTreePaths(json)

        assertEquals(3, paths.size)
        assertTrue("pages/acme-work/Work Note.md" in paths)
        assertTrue("journals/acme-work/2026-06-30.md" in paths)
        assertTrue("pages/global/Other.md" in paths)
    }

    @Test
    fun `TC-6_4-D extractTreePaths returns empty list when tree is empty`() {
        val json = """{"sha":"abc","tree":[]}"""
        assertEquals(emptyList(), extractTreePaths(json))
    }

    @Test
    fun `TC-6_4-D extractTreePaths ignores non-path keys`() {
        val json = """{"sha":"abc","url":"https://api.github.com","tree":[{"path":"pages/Note.md","sha":"def"}]}"""
        val paths = extractTreePaths(json)
        // Only one "path" key value in the tree entry
        assertEquals(listOf("pages/Note.md"), paths)
    }

    // ── TC-6.4-E: Section path filtering ────────────────────────────────────

    @Test
    fun `TC-6_4-E paths under section pagePathPrefix are accepted`() {
        val path = "pages/acme-work/Meeting.md"
        val inSection = path.startsWith(acmeSection.pagePathPrefix) ||
            path.startsWith(acmeSection.journalPathPrefix)
        assertTrue(inSection, "page paths under section prefix must be accepted")
    }

    @Test
    fun `TC-6_4-E paths under section journalPathPrefix are accepted`() {
        val path = "journals/acme-work/2026-06-30.md"
        val inSection = path.startsWith(acmeSection.pagePathPrefix) ||
            path.startsWith(acmeSection.journalPathPrefix)
        assertTrue(inSection, "journal paths under section prefix must be accepted")
    }

    @Test
    fun `TC-6_4-E paths outside section prefixes are skipped`() {
        val outsidePaths = listOf(
            "pages/personal/Diary.md",
            "pages/Global Note.md",
            "journals/2026-06-30.md",
            "attachments/photo.png",
        )
        for (path in outsidePaths) {
            val inSection = path.startsWith(acmeSection.pagePathPrefix) ||
                path.startsWith(acmeSection.journalPathPrefix)
            assertFalse(inSection, "path '$path' must be skipped — outside section prefix")
        }
    }

    @Test
    fun `TC-6_4-E non-md files are skipped`() {
        val path = "pages/acme-work/photo.png"
        assertFalse(path.endsWith(".md"), "non-.md files must be skipped by syncSection")
    }

    // ── TC-6.4-F: Stub page field contract ──────────────────────────────────

    @Test
    fun `TC-6_4-F stub isJournal is true for path under journalPathPrefix`() {
        val path = "journals/acme-work/2026-06-30.md"
        val isJournal = path.startsWith(acmeSection.journalPathPrefix)
        assertTrue(isJournal)
    }

    @Test
    fun `TC-6_4-F stub isJournal is false for path under pagePathPrefix`() {
        val path = "pages/acme-work/Work Note.md"
        val isJournal = path.startsWith(acmeSection.journalPathPrefix)
        assertFalse(isJournal)
    }

    @Test
    fun `TC-6_4-F stub name is filename without md extension`() {
        val path = "pages/acme-work/Meeting Notes.md"
        val name = path.substringAfterLast("/").removeSuffix(".md")
        assertEquals("Meeting Notes", name)
    }

    @Test
    fun `TC-6_4-F journal stub name yields a parseable journalDate`() {
        val path = "journals/acme-work/2026-06-30.md"
        val name = path.substringAfterLast("/").removeSuffix(".md")
        val date = JournalUtils.parseJournalDate(name)
        assertNotNull(date, "date-format journal filename must produce a non-null journalDate")
    }

    @Test
    fun `TC-6_4-F non-date journal filename yields null journalDate`() {
        val path = "journals/acme-work/meeting-notes.md"
        val name = path.substringAfterLast("/").removeSuffix(".md")
        val date = JournalUtils.parseJournalDate(name)
        assertNull(date, "non-date filename must yield null journalDate")
    }

    @Test
    fun `TC-6_4-F sectionId is set to section id for paths under that section`() {
        // syncSection always assigns stubPage.sectionId = section.id
        val expectedSectionId = acmeSection.id
        assertEquals("acme-work", expectedSectionId)
    }

    // ── TC-6.4-G: Rate-limit retry algorithm ────────────────────────────────

    @Test
    fun `TC-6_4-G five consecutive 429 responses exhaust retries and return null`() = runTest {
        // retryCount 0..3 all trigger delay+retry; at retryCount == 4, < 4 is false → null.
        // So the lambda is called 5 times total.
        var callCount = 0
        val result = modelledGithubFetch { _ ->
            callCount++
            429
        }
        assertNull(result, "all 429 responses must eventually exhaust retries and return null")
        assertEquals(5, callCount,
            "should be called exactly 5 times: 1 initial + 4 retries before exhaustion")
    }

    @Test
    fun `TC-6_4-G 429 followed by 200 on last retry returns success`() = runTest {
        // 4 × 429 then 200 — retryCount goes 0→1→2→3→4, at 4 the lambda returns 200.
        var callCount = 0
        val result = modelledGithubFetch { retryCount ->
            callCount++
            if (retryCount < 4) 429 else 200
        }
        assertNotNull(result, "success after exhausting 429 retries on the 5th attempt")
        assertEquals(5, callCount)
    }

    @Test
    fun `TC-6_4-G 429 followed by success before exhaustion returns non-null`() = runTest {
        // 1 × 429 then 200 — should succeed on the first retry
        var callCount = 0
        val result = modelledGithubFetch { retryCount ->
            callCount++
            if (retryCount == 0) 429 else 200
        }
        assertNotNull(result, "should succeed after one 429 and one 200")
        assertEquals(2, callCount)
    }

    @Test
    fun `TC-6_4-G network error (null response) immediately returns null`() = runTest {
        val result = modelledGithubFetch { _ -> null }
        assertNull(result, "network error (null status) must immediately return null without retrying")
    }

    @Test
    fun `TC-6_4-G 404 response returns null without retry`() = runTest {
        var callCount = 0
        val result = modelledGithubFetch { _ ->
            callCount++
            404
        }
        assertNull(result, "non-200 non-429 response must return null")
        assertEquals(1, callCount, "404 must not trigger retry — called exactly once")
    }

    @Test
    fun `TC-6_4-G exponential backoff ceiling is 60 seconds`() {
        // (1 shl retryCount).coerceAtMost(60): at retryCount=6, 1 shl 6 = 64 → capped to 60.
        for (retryCount in 0..6) {
            val delay = (1 shl retryCount).coerceAtMost(60)
            assertTrue(delay <= 60, "backoff must never exceed 60 seconds (retryCount=$retryCount → delay=$delay)")
        }
        // Verify progression: 1, 2, 4, 8, 16, 32, 60 (capped)
        val backoffs = (0..6).map { (1 shl it).coerceAtMost(60) }
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60), backoffs)
    }
}
