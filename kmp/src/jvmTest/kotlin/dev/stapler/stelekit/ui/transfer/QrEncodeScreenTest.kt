@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.1.3 / UX Acceptance Test criteria 1, 7, 13, 16: [QrEncodeScreen] renders the inset-card
 * QR, the pre-flight summary line, the persistent air-gap assertion, and the "Done sending"
 * completion copy without ever implying confirmed delivery (QR has no back-channel).
 */
class QrEncodeScreenTest {

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

    /**
     * Seeds a page whose serialized markdown is exactly 2032 bytes ("- " + 2029 'x' chars + "\n").
     * `QrEncodeViewModel.start` wraps that with [dev.stapler.stelekit.transfer.qrcode.TransferPayloadEnvelope]
     * before it ever reaches `FountainEncoder` — for page name "Meeting Notes" (13 UTF-8 bytes) the
     * envelope header adds exactly 16 bytes ("13\n" + the name itself), landing the wrapped payload
     * back at exactly 2048 bytes, so with `maxFragmentBytes = 171` the pre-flight estimate is still
     * exactly 12 frames — reproducing validation.md's exact fixture copy "Meeting Notes · 5 blocks ·
     * ~2 KB · ~12 frames". `pageName` ("Meeting Notes") and `blockCount` (5) are supplied directly
     * to the screen as caller params (decoupled from the actual fixture block count, per
     * QrEncodeScreen's documented contract).
     */
    private fun buildViewModel(): Pair<QrEncodeViewModel, PageUuid> {
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
                    content = "x".repeat(2029),
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
        val vm = QrEncodeViewModel(pageRepository = pageRepo, blockRepository = blockRepo, settings = settings)
        return vm to pageUuid
    }

    @Test
    fun qrEncodeScreen_should_RenderInsetCardAndPreflightSummary_When_StateIsDisplaying() {
        val (vm, pageUuid) = buildViewModel()

        composeTestRule.setContent {
            QrEncodeScreen(
                pageUuid = pageUuid,
                pageName = "Meeting Notes",
                blockCount = 5,
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.state.value is QrEncodeUiState.Displaying
        }
        composeTestRule.waitForIdle()

        // Pre-flight summary (validation.md's exact fixture copy).
        composeTestRule.onNodeWithText("Meeting Notes · 5 blocks · ~2 KB · ~12 frames").assertIsDisplayed()

        // Inset card — live status contentDescription (AC13), proves the QR canvas rendered.
        composeTestRule.onNodeWithContentDescription("Sending, frame 1 of about 12").assertIsDisplayed()

        // Persistent air-gap assertion.
        composeTestRule.onNodeWithText("No internet connection used", substring = true).assertIsDisplayed()

        vm.close()
    }

    @Test
    fun qrEncodeScreen_should_ShowDoneSendingCopyWithoutConfirmedDeliveryClaim_When_StateIsComplete() {
        val (vm, pageUuid) = buildViewModel()

        composeTestRule.setContent {
            QrEncodeScreen(
                pageUuid = pageUuid,
                pageName = "Meeting Notes",
                blockCount = 5,
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.state.value is QrEncodeUiState.Displaying
        }
        vm.complete()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.state.value is QrEncodeUiState.Complete
        }
        composeTestRule.waitForIdle()

        // UX gap G2: sender has no back-channel — copy must not imply confirmed delivery.
        composeTestRule.onNodeWithText("Sent — ask the other device to confirm it imported").assertIsDisplayed()

        vm.close()
    }
}
