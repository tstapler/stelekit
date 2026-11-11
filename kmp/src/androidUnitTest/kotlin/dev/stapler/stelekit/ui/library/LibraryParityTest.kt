@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryLibraryRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.assets.AssetDetailScreen
import dev.stapler.stelekit.ui.assets.AssetDetailViewModel
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.nav.LegacyScreenAlias
import arrow.core.right
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 6.7 / metric 4: one test method per row of the D.6 parity table (annotation-memory-and-nav-ia plan),
 * each exercising the Library behaviour that replaced the retired Gallery and Assets screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi", sdk = [34])
class LibraryParityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val assets = InMemoryAssetRepository()
    private val annotations = InMemoryImageAnnotationRepository()
    private val measurements = InMemoryMeasurementAnnotationRepository()
    private val library = InMemoryLibraryRepository(assets, annotations, measurements)
    private var vm: LibraryViewModel? = null

    @After
    fun tearDown() { vm?.close() }

    private fun asset(n: Int, name: String, type: AssetMediaType = AssetMediaType.IMAGE, orphan: Boolean = false, imported: Long = 1_000L + n) = runBlocking {
        assets.saveAsset(
            AssetEntry(
                uuid = AssetUuid("asset-%04d".format(n)), filePath = "/g/assets/$name", relativePath = "../assets/$name",
                mediaType = type, subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null,
                cloudDescription = null, pageUuids = if (orphan) emptyList() else listOf("p"), sizeBytes = 2048,
                importedAtMs = imported, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
                contentHash = null, isOrphan = orphan, mlTagsSource = "NONE",
            ),
        )
    }

    private fun annotate(n: Int, name: String, tags: List<String> = emptyList(), measurementCount: Int = 0) = runBlocking {
        val uuid = "ann-%04d".format(n)
        annotations.saveImageAnnotation(
            ImageAnnotation(
                uuid = uuid, blockUuid = "blk-$n", pageUuid = "page-1", graphPath = "/g",
                filePath = "/g/assets/$name", importedAtMs = 5_000L + n, tags = tags,
            ),
        )
        repeat(measurementCount) { i ->
            measurements.saveMeasurementAnnotation(
                MeasurementAnnotation(
                    uuid = "m-$n-$i", imageUuid = uuid, annotationType = AnnotationType.DISTANCE,
                    normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.9)), label = "w$i",
                ),
            )
        }
    }

    private class Taps {
        val editor = mutableListOf<String>()
        val detail = mutableListOf<String>()
        val links = mutableListOf<String>()
        var addPhoto = 0
    }

    private fun show(
        taps: Taps = Taps(),
        fileExists: ((String) -> Boolean)? = null,
        delete: (suspend (String) -> arrow.core.Either<dev.stapler.stelekit.error.DomainError, Unit>)? = null,
        withAddPhoto: Boolean = false,
    ): LibraryViewModel {
        val model = LibraryViewModel(library, searchDebounceMs = 10, fileExists = fileExists, deleteAsset = delete)
        vm = model
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    model, { taps.editor += it }, { taps.detail += it },
                    onAddPhoto = if (withAddPhoto) ({ taps.addPhoto++ }) else null,
                    onCopyLink = { taps.links += it },
                )
            }
        }
        return model
    }

    private fun openFilters() = composeRule.onNodeWithTag("library-filters-button").performClick()

    private fun waitForCount(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodes(SemanticsMatcher("count line is '$text'") {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } == text
        }).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String, unmerged: Boolean = false) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun sortOrderMenu() {
        asset(1, "b.png", imported = 1_000L); asset(2, "a.png", imported = 2_000L); asset(3, "c.png", imported = 3_000L)
        val model = show()
        waitForCount("3 items")
        assertEquals(listOf("c.png", "a.png", "b.png"), model.state.value.items.map { it.fileName })
        composeRule.onNodeWithTag("library-sort").performClick()
        composeRule.onNodeWithTag("library-sort-NAME").performClick()
        composeRule.waitUntil(10_000) { model.state.value.items.map { it.fileName } == listOf("a.png", "b.png", "c.png") }
        assertEquals(LibrarySort.NAME, model.state.value.sort)
    }

    @Test
    fun tagChips() {
        asset(1, "a.png"); asset(2, "b.png"); asset(3, "c.png")
        annotate(1, "a.png", tags = listOf("site")); annotate(2, "b.png", tags = listOf("kitchen"))
        show()
        waitForCount("3 items")
        openFilters()
        waitForTag("library-chip-tag-site")
        composeRule.onNodeWithTag("library-chip-tag-kitchen").assertIsDisplayed()
        composeRule.onNodeWithTag("library-chip-tag-site").performClick()
        waitForCount("1 item")
    }

    @Test
    fun annotationThumbnailAndMeasurementCount() {
        asset(1, "a.png"); annotate(1, "a.png", measurementCount = 2)
        show()
        waitForCount("1 item")
        // The card merges its children into one spoken description, so the visual badge is read from the unmerged tree.
        composeRule.onNodeWithTag("library-badge-annotated", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("${I18n.get(I18n.LIBRARY_BADGE_ANNOTATED)} · 2", useUnmergedTree = true).assertExists()
        val description = composeRule.onNodeWithTag("library-card-asset-0001").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description.contains("2 measurements"), description)
    }

    @Test
    fun tapOpensEditor() {
        asset(1, "annotated.png"); asset(2, "plain.png")
        annotate(1, "annotated.png")
        val taps = Taps()
        show(taps)
        waitForCount("2 items")
        composeRule.onNodeWithTag("library-card-asset-0001").performClick()
        composeRule.onNodeWithTag("library-card-asset-0002").performClick()
        assertEquals(listOf("ann-0001"), taps.editor)
        assertEquals(listOf("asset-0002"), taps.detail)
    }

    @Test
    fun missingFileHandling() {
        asset(1, "here.png"); asset(2, "gone.png")
        show(fileExists = { !it.endsWith("gone.png") })
        waitForTag("library-missing-asset-0002", unmerged = true)
        composeRule.onAllNodesWithTag("library-missing-asset-0001", useUnmergedTree = true).assertCountEquals(0)
        val description = composeRule.onNodeWithTag("library-card-asset-0002").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description.endsWith(I18n.get(I18n.LIBRARY_MISSING)), description)
    }

    @Test
    fun emptyStateAndCapturePhoto() {
        val taps = Taps()
        show(taps, withAddPhoto = true)
        waitForTag("library-empty")
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_EMPTY_TITLE)).assertIsDisplayed()
        composeRule.onNodeWithTag("library-add").performClick()
        assertEquals(1, taps.addPhoto)
    }

    @Test
    fun importFromGalleryStubIsAbsent() {
        show(withAddPhoto = true)
        waitForTag("library-empty")
        composeRule.onAllNodes(hasText("Import from Gallery", substring = true, ignoreCase = true)).assertCountEquals(0)
        composeRule.onAllNodesWithText("Import", substring = true).assertCountEquals(0)
    }

    @Test
    fun searchField() {
        asset(1, "kitchen.png"); asset(2, "bath.png")
        show()
        waitForCount("2 items")
        composeRule.onNodeWithTag("library-search").performTextInput("kit")
        waitForCount("1 item")
    }

    @Test
    fun typeFilterChips() {
        asset(1, "a.png"); asset(2, "b.pdf", AssetMediaType.PDF); asset(3, "c.mp4", AssetMediaType.VIDEO)
        show()
        waitForCount("3 items")
        openFilters()
        composeRule.onNodeWithTag("library-chip-type-PDFS").performClick()
        waitForCount("1 item")
        composeRule.onNodeWithTag("library-chip-type-IMAGES").performClick()
        waitForCount("2 items")
    }

    @Test
    fun orphanedFilter() {
        asset(1, "kept.png"); asset(2, "lost.png", orphan = true)
        show()
        waitForCount("2 items")
        openFilters()
        composeRule.onNodeWithTag("library-chip-orphaned").performClick()
        waitForCount("1 item")
    }

    @Test
    fun statusLine() {
        repeat(3) { asset(it, "p$it.png") }
        show()
        waitForCount("3 items")
        composeRule.onNodeWithTag("library-count").assertTextContains("3 items")
        assertTrue(composeRule.onNodeWithTag("library-count").fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun perAssetActionsMenu() {
        asset(1, "doc.png")
        val taps = Taps()
        val deleted = mutableListOf<String>()
        show(taps, delete = { id -> deleted += id; Unit.right() })
        waitForCount("1 item")
        composeRule.onNodeWithTag("library-card-menu-asset-0001").performClick()
        composeRule.onNodeWithTag("library-action-Details").assertIsDisplayed()
        composeRule.onNodeWithTag("library-action-CopyLink").performClick()
        assertEquals(listOf("![doc.png](../assets/doc.png)"), taps.links)
        composeRule.onNodeWithTag("library-card-menu-asset-0001").performClick()
        composeRule.onNodeWithTag("library-action-Delete").performClick()
        composeRule.onNodeWithTag("library-delete-confirm").performClick()
        composeRule.waitUntil(10_000) { deleted.isNotEmpty() }
        assertEquals(listOf("asset-0001"), deleted)
    }

    @Test
    fun assetDetailAndAnnotate() {
        asset(1, "photo.png")
        val model = AssetDetailViewModel(assetRepository = assets, assetUuid = AssetUuid("asset-0001"))
        val annotated = mutableListOf<String>()
        composeRule.setContent {
            AssetDetailScreen(viewModel = model, onNavigateBack = {}, onNavigateToPage = {}, onAnnotate = { annotated += it.uuid.value })
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(SemanticsMatcher("annotate button") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Annotate / measure image") == true
        }).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Annotate / measure image").performClick()
        assertEquals(listOf("asset-0001"), annotated)
    }

    @Test
    fun globalSearchShowAllRedirectsToLibrarySearch() {
        assertEquals(Screen.Library(initialSearch = "kitchen"), LegacyScreenAlias.resolve(Screen.AssetBrowser("kitchen")))
        assertEquals(Screen.Library(initialFilter = LibraryFilter.Annotated), LegacyScreenAlias.resolve(Screen.Gallery))
    }
}
