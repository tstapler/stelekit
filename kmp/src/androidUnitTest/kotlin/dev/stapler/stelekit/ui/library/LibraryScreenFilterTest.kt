@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryLibraryRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.i18n.I18n
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library filters, sort, states and the tap rule over the in-memory backend (plan D.6; the SQL is covered by the parity test). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class LibraryScreenFilterTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val assets = InMemoryAssetRepository()
    private val annotations = InMemoryImageAnnotationRepository()
    private val library = InMemoryLibraryRepository(assets, annotations, InMemoryMeasurementAnnotationRepository())
    private var vm: LibraryViewModel? = null

    @After
    fun tearDown() { vm?.close() }

    private fun asset(n: Int, name: String, type: AssetMediaType = AssetMediaType.IMAGE, orphan: Boolean = false) = runBlocking {
        assets.saveAsset(
            AssetEntry(
                uuid = AssetUuid("asset-%04d".format(n)), filePath = "/g/assets/$name", relativePath = "../assets/$name",
                mediaType = type, subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null,
                cloudDescription = null, pageUuids = if (orphan) emptyList() else listOf("p"), sizeBytes = 2048,
                importedAtMs = 1_000L + n, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
                contentHash = null, isOrphan = orphan, mlTagsSource = "NONE",
            ),
        )
    }

    private fun annotate(n: Int, name: String, tags: List<String> = emptyList()) = runBlocking {
        annotations.saveImageAnnotation(
            ImageAnnotation(
                uuid = "ann-%04d".format(n), blockUuid = "blk-$n", pageUuid = "page-1", graphPath = "/g",
                filePath = "/g/assets/$name", importedAtMs = 5_000L + n, tags = tags,
            ),
        )
    }

    private fun seedHundredWithSevenAnnotated() {
        repeat(100) { n -> asset(n, "photo$n.png") }
        listOf(3, 14, 25, 36, 47, 58, 69).forEach { annotate(it, "photo$it.png", tags = listOf("site")) }
    }

    /** The card announces annotation state in words (the visual badge is icon plus word and is not read twice). */
    private val isAnnotatedCard = SemanticsMatcher("annotated card") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Annotated photo") } == true
    }

    private class Taps {
        val editor = mutableListOf<String>()
        val detail = mutableListOf<String>()
    }

    private fun show(taps: Taps = Taps(), filters: LibraryFilterState = LibraryFilterState(), onboarding: LibraryOnboardingState? = null): LibraryViewModel {
        val model = LibraryViewModel(library, initialFilters = filters, searchDebounceMs = 10)
        vm = model
        composeRule.setContent {
            MaterialTheme {
                if (onboarding != null) {
                    LibraryScreen(model, { taps.editor += it }, { taps.detail += it }, onboarding = onboarding)
                } else {
                    LibraryScreen(model, { taps.editor += it }, { taps.detail += it })
                }
            }
        }
        return model
    }

    /** At 411 dp the chip groups sit behind the single Filters button (LD-6). */
    private fun openFilters() = composeRule.onNodeWithTag("library-filters-button").performClick()

    private fun waitForCount(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodes(SemanticsMatcher("count line is '$text'") {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } == text
        }).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun annotatedChip_listsExactlySevenCards_andClearRestoresTheFullList() {
        seedHundredWithSevenAnnotated()
        show()
        waitForCount("100 items")
        openFilters()
        composeRule.onNodeWithTag("library-chip-annotated").performClick()
        waitForCount("7 items")
        composeRule.onAllNodes(isAnnotatedCard).assertCountEquals(7)
        composeRule.onNodeWithTag("library-clear").performClick()
        waitForCount("100 items")
    }

    @Test
    fun aDimmedChip_isFocusableCarriesItsReason_andTappingItShowsTheReasonWithoutChangingFilters() {
        seedHundredWithSevenAnnotated()
        val model = show(filters = LibraryFilterState(annotated = true))
        waitForCount("7 items")
        openFilters()
        composeRule.onNode(
            hasTestTag("library-chip-type-PDFS").and(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, I18n.get(I18n.LIBRARY_REASON_ANNOTATE_NEEDS_PHOTOS)),
            ),
        ).assertExists()
        composeRule.onNodeWithTag("library-chip-type-PDFS").performClick()
        composeRule.onNodeWithTag("library-reason").assertTextContains(I18n.get(I18n.LIBRARY_REASON_ANNOTATE_NEEDS_PHOTOS))
        assertEquals(LibraryFilterState(annotated = true), model.state.value.filters)
    }

    @Test
    fun typeChipsAreOrWithinTheGroup() {
        repeat(4) { asset(it, "img$it.png") }
        repeat(3) { asset(10 + it, "doc$it.pdf", AssetMediaType.PDF) }
        repeat(2) { asset(20 + it, "clip$it.mp4", AssetMediaType.VIDEO) }
        show()
        waitForCount("9 items")
        openFilters()
        composeRule.onNodeWithTag("library-chip-type-IMAGES").performClick()
        composeRule.onNodeWithTag("library-chip-type-PDFS").performClick()
        waitForCount("7 items")
    }

    @Test
    fun orphanedChip_listsOnlyOrphans() {
        asset(1, "kept.png")
        asset(2, "lost.png", orphan = true)
        show()
        waitForCount("2 items")
        openFilters()
        composeRule.onNodeWithTag("library-chip-orphaned").performClick()
        waitForCount("1 item")
    }

    @Test
    fun oneCharacterSearch_showsTheHint_andDoesNotFilter() {
        repeat(5) { asset(it, "kitchen$it.png") }
        show()
        waitForCount("5 items")
        composeRule.onNodeWithTag("library-search").performTextInput("k")
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_SEARCH_HINT)).assertIsDisplayed()
        waitForCount("5 items")
    }

    @Test
    fun cards_haveUniqueSpokenDescriptions_andTheAnnotatedBadgeIsIconPlusWord() {
        asset(1, "a.png"); asset(2, "b.png")
        annotate(1, "a.png"); annotate(2, "b.png")
        show()
        waitForCount("2 items")
        val descriptions = (1..2).map {
            composeRule.onNodeWithTag("library-card-asset-%04d".format(it)).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].single()
        }
        assertEquals(2, descriptions.toSet().size)
        assertTrue(descriptions.all { it.contains("Annotated photo") && it.contains("measurement") })
        composeRule.onAllNodes(isAnnotatedCard).assertCountEquals(2)
    }

    @Test
    fun tapRule_annotatedImageOpensTheEditor_anyOtherCardOpensDetail() {
        asset(1, "annotated.png"); asset(2, "plain.png"); asset(3, "doc.pdf", AssetMediaType.PDF)
        annotate(1, "annotated.png")
        val taps = Taps()
        show(taps)
        waitForCount("3 items")
        composeRule.onNodeWithTag("library-card-asset-0001").performClick()
        composeRule.onNodeWithTag("library-card-asset-0002").performClick()
        composeRule.onNodeWithTag("library-card-asset-0003").performClick()
        assertEquals(listOf("ann-0001"), taps.editor)
        assertEquals(listOf("asset-0002", "asset-0003"), taps.detail)
    }

    @Test
    fun emptyLibrary_explainsWhereNewFilesGo_andOffersNoImportFromGalleryStub() {
        show()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("library-empty").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_EMPTY_TITLE)).assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText("Import from Gallery")).assertCountEquals(0)
    }

    @Test
    fun filteredEmpty_offersClearFilters() {
        asset(1, "a.png")
        show(filters = LibraryFilterState(annotated = true))
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("library-empty").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_EMPTY_ANNOTATED)).assertIsDisplayed()
    }

    @Test
    fun compactWidth_collapsesChipGroupsBehindOneFiltersButton() {
        asset(1, "a.png")
        show()
        waitForCount("1 item")
        composeRule.onNodeWithTag("library-filters-button").assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag("library-chip-annotated")).assertCountEquals(0)
        composeRule.onNodeWithTag("library-filters-button").performClick()
        composeRule.onNodeWithTag("library-chip-annotated").assertIsDisplayed()
    }

    @Test
    fun upgradeNotice_showsOnceWithItsActions_andTheTipIsNotAlsoShown() {
        asset(1, "a.png")
        show(onboarding = LibraryOnboardingState(mode = LibraryOnboardingMode.UPGRADE))
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_NOTICE)).assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText(I18n.get(I18n.LIBRARY_TIP))).assertCountEquals(0)
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_NOTICE_DISMISS)).assertIsDisplayed()
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_NOTICE_SHOW_ANNOTATED)).assertIsDisplayed()
    }

    @Test
    fun freshInstall_showsTheTip() {
        show(onboarding = LibraryOnboardingState(mode = LibraryOnboardingMode.FRESH))
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_TIP)).assertIsDisplayed()
    }

    @Test
    fun titleIsAHeading() {
        show()
        composeRule.onNode(
            hasTestTag("library-title").and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)),
        ).assertExists()
    }
}
