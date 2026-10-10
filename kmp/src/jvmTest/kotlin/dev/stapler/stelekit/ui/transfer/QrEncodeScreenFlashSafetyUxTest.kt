@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/**
 * UX Acceptance Test criterion 16 (`validation.md`, `design/ux.md` §12, ADR-004): WCAG 2.3.1
 * flash-safety — ≤3fps default frame-advance, ≤60% viewport inset card, ≤2fps reduce-motion
 * tap-advance ceiling enforced by the app itself (not just human tap speed).
 */
class QrEncodeScreenFlashSafetyUxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    /** Seeds a page with a single block whose content is exactly [contentLength] ASCII bytes. */
    private fun fixture(contentLength: Int = 400): Triple<InMemoryPageRepository, InMemoryBlockRepository, PageUuid> {
        val now = Clock.System.now()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        runBlocking {
            pageRepo.savePage(Page(uuid = pageUuid, name = "Meeting Notes", createdAt = now, updatedAt = now))
            blockRepo.saveBlock(
                Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000010"),
                    pageUuid = pageUuid,
                    content = "x".repeat(contentLength),
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return Triple(pageRepo, blockRepo, pageUuid)
    }

    @Test
    fun frameAdvance_should_NeverExceed3Fps_When_DisplayingAtDefaultSettings() = runBlocking {
        // Default settings -> ADR-004's 2.5fps (400ms/frame), well inside the <=3fps/300ms ceiling.
        // Same tick-injection seam as QrEncodeViewModelTest (Story 3.1.2) — every ms value passed
        // into tick() IS the interval the real `delay(ms)` call would honor in production, so
        // asserting on it is equivalent to timing real frame changes without a single real sleep.
        val (pageRepo, blockRepo, pageUuid) = fixture()
        val tickChannel = Channel<Unit>(Channel.RENDEZVOUS)
        val capturedIntervals = mutableListOf<Long>()
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
            tick = { ms -> synchronized(capturedIntervals) { capturedIntervals.add(ms) }; tickChannel.receive() },
        )

        vm.start(pageUuid)
        withTimeout(5_000) { vm.state.first { it is QrEncodeUiState.Displaying } }

        // 25 ticks x 400ms/frame == 10,000ms of virtual time, matching the >=10s window criterion
        // 16 requires without ever sleeping in real time.
        val frameCountFor10Seconds = 25
        repeat(frameCountFor10Seconds) { tickChannel.send(Unit) }
        withTimeout(5_000) {
            vm.state.first { (it as? QrEncodeUiState.Displaying)?.totalCycled == frameCountFor10Seconds }
        }

        synchronized(capturedIntervals) {
            assertTrue(capturedIntervals.size >= frameCountFor10Seconds)
            assertTrue(
                capturedIntervals.all { it >= 300L },
                "expected every frame interval >=300ms (<=3.33fps WCAG 2.3.1 ceiling), got $capturedIntervals",
            )
        }

        vm.close()
    }

    @Test
    fun insetCard_should_OccupyAtMost60PercentOfViewportArea_When_Displaying() {
        val (pageRepo, blockRepo, pageUuid) = fixture()
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(pageRepository = pageRepo, blockRepository = blockRepo, settings = settings)

        composeTestRule.setContent {
            QrEncodeScreen(
                pageUuid = pageUuid,
                pageName = "Meeting Notes",
                blockCount = 5,
                viewModel = vm,
                settings = settings,
                onDismiss = {},
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Displaying }
        composeTestRule.waitForIdle()

        // The inset card is the only node whose live contentDescription starts with "Sending,
        // frame" (QrEncodeScreen's DisplayingContent) — anchor on that rather than a testTag,
        // since none is defined in production code and adding one is out of this task's scope.
        val insetBounds = composeTestRule
            .onNodeWithContentDescription("Sending, frame", substring = true)
            .getBoundsInRoot()
        val rootBounds = composeTestRule.onRoot().getBoundsInRoot()

        val insetArea = (insetBounds.right - insetBounds.left).value * (insetBounds.bottom - insetBounds.top).value
        val rootArea = (rootBounds.right - rootBounds.left).value * (rootBounds.bottom - rootBounds.top).value
        val fraction = insetArea / rootArea

        assertTrue(
            fraction <= 0.60,
            "inset card occupies ${fraction * 100}% of viewport area, must be <=60% (WCAG 2.3.1 small-area exemption)",
        )

        vm.close()
    }

    @Test
    fun reduceMotion_should_CapAdvanceRateAt2Fps_When_NextButtonHeldOrRapidlyTapped() {
        // Same fake-clock seam as QrEncodeViewModelTest's advanceFrame_should_CapAdvanceRateAt2Fps
        // test, but driven through real onNodeWithText(...).performClick() taps on the actual
        // QrEncodeScreen composable instead of calling vm.advanceFrame() directly — a genuine
        // UI-level regression test for the same MIN_TAP_ADVANCE_INTERVAL guard (commit c8f96ae474).
        var now = Clock.System.now()
        val (pageRepo, blockRepo, pageUuid) = fixture()
        val settings = QrTransferSettings(MapSettings()).apply {
            maxFragmentBytes = 16
            reduceMotion = true
        }
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
            clock = { now },
        )

        composeTestRule.setContent {
            QrEncodeScreen(
                pageUuid = pageUuid,
                pageName = "Meeting Notes",
                blockCount = 5,
                viewModel = vm,
                settings = settings,
                onDismiss = {},
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Displaying }
        composeTestRule.waitForIdle()

        val nextButton = composeTestRule.onNodeWithText("Next ▶")

        // 10 taps crammed into 200ms of virtual time (20ms apart) — well under the 500ms/frame
        // (2fps) ceiling. Only the first tap should be granted.
        repeat(10) {
            now += 20.milliseconds
            nextButton.performClick()
            composeTestRule.waitForIdle()
        }
        assertEquals(1, assertIs<QrEncodeUiState.Displaying>(vm.state.value).totalCycled, "rapid taps within 200ms must yield at most one advance")

        // Still under the ceiling (~380ms since the granted advance) — must still be rejected.
        now += 180.milliseconds
        nextButton.performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, assertIs<QrEncodeUiState.Displaying>(vm.state.value).totalCycled)

        // Crosses the 500ms ceiling from the last granted advance — this tap must be granted.
        now += 150.milliseconds
        nextButton.performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, assertIs<QrEncodeUiState.Displaying>(vm.state.value).totalCycled)

        vm.close()
    }
}
