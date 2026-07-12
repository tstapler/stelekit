package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * UX Acceptance Test criteria 7 and 13 (validation.md, encoder half): [QrEncodeScreen] must show
 * the actual + max byte counts (not "too big") when serialization fails pre-flight, and the QR
 * canvas's `contentDescription` must live-update with the current frame index as `Displaying`
 * advances.
 */
class QrEncodeScreenUxTest {

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

    private fun seedPage(pageUuid: PageUuid, contentLength: Int): Pair<InMemoryPageRepository, InMemoryBlockRepository> {
        val now = Clock.System.now()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
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
        return pageRepo to blockRepo
    }

    @Test
    fun payloadTooLarge_should_ShowActualAndMaxSizeInMessage_When_SerializingFailsPreflight() {
        // 70_000-byte block content trips FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES (65536) before
        // any frame is ever displayed — same fixture QrEncodeViewModelTest uses for this gate.
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 70_000)
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(pageRepository = pageRepo, blockRepository = blockRepo, settings = settings)

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

        composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Failed }
        composeTestRule.waitForIdle()

        val error = assertIs<QrEncodeUiState.Failed>(vm.state.value).error
        val payloadTooLarge = assertIs<DomainError.QrTransferError.PayloadTooLarge>(error)

        // Concrete numbers, not a vague "too big" — proves the screen special-cases this variant
        // rather than falling back to the six-variant-generic toUiMessage().
        composeTestRule
            .onNodeWithText("${payloadTooLarge.sizeBytes} bytes", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("max ${payloadTooLarge.maxBytes} bytes", substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Back").assertIsDisplayed().assertIsEnabled()

        vm.close()
    }

    @Test
    fun qrCanvas_should_UpdateContentDescriptionWithFrameIndex_When_DisplayingStateAdvances() {
        // Same fixture as QrEncodeScreenTest.buildViewModel(): 2029-char block + maxFragmentBytes
        // 171 -> wrapped in TransferPayloadEnvelope (16 bytes overhead for "Meeting Notes") ->
        // exactly 2048 wrapped bytes -> exactly 12 frames, a known-good chunkCount.
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 2029)
        var now = Clock.System.now()
        val settings = QrTransferSettings(MapSettings()).apply {
            maxFragmentBytes = 171
            reduceMotion = true // tap-to-advance only, driven deterministically via advanceFrame()
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
        composeTestRule.onNodeWithContentDescription("Sending, frame 1 of about 12").assertIsDisplayed()

        // Advance to frameIndex=4 (totalCycled=4, chunkCount=12 so frameIndex==totalCycled) —
        // each tap separated by >500ms of virtual time so none is rejected by the 2fps ceiling.
        repeat(4) {
            now += 600.milliseconds
            vm.advanceFrame()
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrEncodeUiState.Displaying)?.frameIndex == 4
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sending, frame 5 of about 12").assertIsDisplayed()

        // Advance one more frame (frameIndex=5) — contentDescription must update again, live.
        now += 600.milliseconds
        vm.advanceFrame()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrEncodeUiState.Displaying)?.frameIndex == 5
        }
        composeTestRule.waitForIdle()
        // Live update, not an additional node — the old description must be gone.
        composeTestRule.onNodeWithContentDescription("Sending, frame 6 of about 12").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Sending, frame 5 of about 12").assertCountEquals(0)

        vm.close()
    }
}
