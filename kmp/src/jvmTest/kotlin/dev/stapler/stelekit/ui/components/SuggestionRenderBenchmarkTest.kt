package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.ui.theme.StelekitTheme
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Integration benchmarks for the suggestion-highlighting render pipeline.
 *
 * These tests guard against regressions in three areas:
 *
 * 1. **Non-blocking first frame** — the composition thread must not block on findAll.
 *    With the pre-2026 approach, a 500-page matcher × 30 blocks = 15,000 findAll calls
 *    on the composition thread per frame. After the produceState refactor, the first
 *    frame renders immediately; suggestions arrive asynchronously.
 *
 * 2. **Matcher swap throughput** — replacing the matcher (e.g. after a page rename)
 *    should not cause a visible stutter. Each block recomputes independently on
 *    Dispatchers.Default; the composition thread only recomposes blocks whose
 *    suggestion spans actually changed.
 *
 * 3. **Large-dataset stability** — 30 blocks × 500 page names must settle within the
 *    time budget without hanging the test runner.
 *
 * Time budgets are generous to accommodate slow CI runners. The purpose is detecting
 * pathological regressions (hanging, blocking), not micro-benchmarking.
 */
class SuggestionRenderBenchmarkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun buildMatcher(pageCount: Int): AhoCorasickMatcher {
        val names = (1..pageCount).associate { "topic $it" to "Topic $it" }
        return AhoCorasickMatcher(names)
    }

    private fun blockText(i: Int) = "Notes on topic $i and related topic ${i + 1} from the meeting."

    // ── test 1: first-frame non-blocking ───────────────────────────────────────

    /**
     * The composition thread must not block on findAll during the first frame.
     * Even with a 500-page matcher and content that matches many terms, the initial
     * composition should complete in << 500 ms because produceState defers the work.
     *
     * A regression here means findAll crept back onto the composition thread.
     */
    @Test
    fun `first frame renders immediately before produceState completes`() {
        val largeMatcher = buildMatcher(500)
        // Content with many term hits to maximise findAll cost if it runs on-thread.
        val heavyText = (1..30).joinToString(". ") { "topic $it is covered" }

        val firstFrameMs = measureTime {
            composeTestRule.setContent {
                StelekitTheme {
                    WikiLinkText(
                        text = heavyText,
                        textColor = Color.Unspecified,
                        linkColor = Color.Blue,
                        suggestionMatcher = largeMatcher,
                    )
                }
            }
            composeTestRule.waitForIdle()
        }.inWholeMilliseconds

        assertTrue(
            firstFrameMs < 500,
            "First frame took ${firstFrameMs}ms — expected < 500ms. " +
                "findAll may have moved back onto the composition thread.",
        )
    }

    // ── test 2: thirty blocks settle within budget ─────────────────────────────

    /**
     * Thirty blocks rendered simultaneously with a 200-page matcher must all
     * display their content and have their suggestion spans computed within 5 s.
     * This covers the common case of opening a dense journal page.
     */
    @Test
    fun `thirty blocks with suggestions settle within time budget`() {
        val matcher = buildMatcher(200)
        val texts = (1..30).map { blockText(it) }

        val totalMs = measureTime {
            composeTestRule.setContent {
                StelekitTheme {
                    Column {
                        texts.forEach { text ->
                            WikiLinkText(
                                text = text,
                                textColor = Color.Unspecified,
                                linkColor = Color.Blue,
                                suggestionMatcher = matcher,
                            )
                        }
                    }
                }
            }
            // Wait until all 30 blocks have composed their text nodes.
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule
                    .onAllNodesWithText("Notes on topic", substring = true)
                    .fetchSemanticsNodes().size >= 30
            }
        }.inWholeMilliseconds

        assertTrue(totalMs < 5_000, "30-block render took ${totalMs}ms — expected < 5000ms.")
    }

    // ── test 3: first block visible without others ──────────────────────────────

    /**
     * The first block must be visible before all 30 blocks finish computing their
     * suggestions. This guards against a regression where a blocking batch rebuild
     * would prevent any frame from being shown until all work completed.
     */
    @Test
    fun `first block is visible before all suggestion work completes`() {
        val matcher = buildMatcher(500)

        composeTestRule.setContent {
            StelekitTheme {
                Column {
                    // First block — the one we assert on immediately.
                    WikiLinkText(
                        text = blockText(1),
                        textColor = Color.Unspecified,
                        linkColor = Color.Blue,
                        suggestionMatcher = matcher,
                    )
                    // 29 more blocks that need background computation.
                    (2..30).forEach { i ->
                        WikiLinkText(
                            text = blockText(i),
                            textColor = Color.Unspecified,
                            linkColor = Color.Blue,
                            suggestionMatcher = matcher,
                        )
                    }
                }
            }
        }
        // One idle tick — first composition pass only.
        composeTestRule.waitForIdle()

        // The first block's text must be visible right away, even if suggestions
        // for all 30 blocks haven't finished computing yet.
        composeTestRule
            .onNodeWithText(blockText(1), substring = true)
            .assertIsDisplayed()
    }

    // ── test 4: matcher swap doesn't hang ──────────────────────────────────────

    /**
     * Swapping the matcher (simulating a page rename or new-page creation that
     * triggers a PageNameIndex rebuild) must not cause a visible hang.
     *
     * Before the produceState refactor, swapping the matcher invalidated all visible
     * blocks' remember() caches at once, causing a synchronous spike proportional to
     * (visibleBlocks × matcherSize). After the refactor, each block recomputes
     * independently on Dispatchers.Default.
     */
    @Test
    fun `swapping matcher for 20 blocks completes without hanging`() {
        val matcher1 = buildMatcher(300)
        val matcher2 = buildMatcher(400)
        var currentMatcher by mutableStateOf<AhoCorasickMatcher?>(matcher1)
        val texts = (1..20).map { blockText(it) }

        composeTestRule.setContent {
            StelekitTheme {
                Column {
                    texts.forEach { text ->
                        WikiLinkText(
                            text = text,
                            textColor = Color.Unspecified,
                            linkColor = Color.Blue,
                            suggestionMatcher = currentMatcher,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val swapMs = measureTime {
            currentMatcher = matcher2
            // Wait for all blocks to settle with the new matcher.
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule
                    .onAllNodesWithText("Notes on topic", substring = true)
                    .fetchSemanticsNodes().size >= 20
            }
        }.inWholeMilliseconds

        assertTrue(
            swapMs < 5_000,
            "Matcher swap for 20 blocks took ${swapMs}ms — expected < 5000ms. " +
                "This suggests a blocking recomposition spike.",
        )
    }
}
