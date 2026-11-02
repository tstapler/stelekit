package dev.stapler.stelekit.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.asset.pipeline.CLOUD_EXIF_REMOVED_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_EXIF_SENT_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_NETWORK_ONLINE_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_NETWORK_UNMETERED_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_STORAGE_LINE
import dev.stapler.stelekit.asset.pipeline.CLOUD_WARNING_LINE
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudConsentDialogModel
import dev.stapler.stelekit.ui.components.settings.CloudConsentDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The consent dialog's wording and choices, driven with fixed models. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CloudConsentDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun model(
        existing: Long = 0,
        exif: Boolean = true,
        metered: Boolean = true,
        session: Int = 20,
        daily: Int = 100,
    ) = CloudConsentDialogModel(
        graphName = "Work notes", existingImages = existing, estimatedDays = if (existing == 0L) 0 else ((existing + daily - 1) / daily).toInt(),
        sessionCap = session, dailyCap = daily, exifStripShipped = exif, meteredAware = metered, launchCount = 3, dailyCount = 12,
    )

    private fun show(model: CloudConsentDialogModel, onEnable: (CloudBackfillScope) -> Unit = {}, onCancel: () -> Unit = {}) =
        composeRule.setContent { CloudConsentDialog(model, onEnable, onCancel) }

    private fun assertText(text: String) = composeRule.onNodeWithText(text, substring = true).assertExists()

    @Test
    fun dialog_should_ShowAnthropicImagesLiveCapsThirdPartySentenceAndButtons_When_Opened() {
        show(model(session = 7, daily = 33))

        assertText("Send images to Anthropic?")
        assertText("Image bytes are sent to Anthropic.")
        assertText(CLOUD_WARNING_LINE)
        assertText("Up to 7 images per app launch, 33 per day")
        assertText("This launch: 3 of 7, all graphs")
        assertText("Today: 12 of 33, all graphs")
        assertText("Applies to graph: Work notes")
        composeRule.onNodeWithText("Enable uploads").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun consentDialog_should_LeaveSwitchOffAndNotAskedUnchanged_When_CancelEscapeBackOrOutsideTap() {
        var enabled = 0
        var cancelled = 0
        show(model(), onEnable = { enabled++ }, onCancel = { cancelled++ })

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, enabled, "cancel never records consent")
        assertEquals(1, cancelled)
    }

    @Test
    fun dialog_should_OmitExifLine_When_ExifStripperNotShipped_And_ShowItWhenShipped() {
        show(model(exif = false))
        assertText(CLOUD_EXIF_SENT_LINE)
        composeRule.onNodeWithText(CLOUD_EXIF_REMOVED_LINE, substring = true).assertDoesNotExist()
    }

    @Test
    fun dialog_should_ShowRemovedLine_When_ExifStripperShipped() {
        show(model(exif = true))
        assertText(CLOUD_EXIF_REMOVED_LINE)
        composeRule.onNodeWithText(CLOUD_EXIF_SENT_LINE, substring = true).assertDoesNotExist()
    }

    @Test
    fun dialog_should_HideExistingImagesOptionAtZeroCount_And_AlwaysSayDescriptionsAreSavedAndSearchable_When_Opened() {
        show(model(existing = 0))

        assertText(CLOUD_STORAGE_LINE)
        composeRule.onNodeWithText("Also the", substring = true).assertDoesNotExist()
    }

    @Test
    fun dialog_should_NameGraphDefaultToNewOnlyAndShowLiveBacklogCountAndDayEstimate_When_Opened() {
        var chosen: CloudBackfillScope? = null
        show(model(existing = 1760), onEnable = { chosen = it })

        assertText("Applies to graph: Work notes")
        assertText("Also the 1760 images already in this graph, at most 100 a day, about 18 days")
        composeRule.onNodeWithText("Enable uploads").performClick()
        assertEquals(CloudBackfillScope.NewOnly, chosen, "the default is the narrow scope")

        chosen = null
        composeRule.onNodeWithText("Also the", substring = true).performScrollTo().performClick()
        composeRule.onNodeWithText("Enable uploads").performClick()
        assertEquals(CloudBackfillScope.IncludeExisting, chosen)
    }

    @Test
    fun networkLine_should_SayUnmeteredOnAndroidAndOnlyWhileOnlineOnDesktop_And_NoUploadAnywayControlExists() {
        show(model(metered = true))
        assertText(CLOUD_NETWORK_UNMETERED_LINE)
        composeRule.onNodeWithText("anyway", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun networkLine_should_SayOnlyWhileOnline_When_PlatformCannotTellMetered() {
        show(model(metered = false))
        assertText(CLOUD_NETWORK_ONLINE_LINE)
        composeRule.onNodeWithText("unmetered", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun capCopy_should_SayPerAppLaunchAndPerDayNeverSession_When_Rendered() {
        show(model())
        assertText("per app launch")
        composeRule.onNodeWithText("session", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
