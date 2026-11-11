// jvmTest-reason: desktop-only — drives the Library screen on Compose Desktop (real AWT renderer) over real SQLDelight; the Robolectric twin is LibraryScreenFilterTest
@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import dev.stapler.stelekit.repository.SqlDelightLibraryRepository
import dev.stapler.stelekit.ui.i18n.I18n
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertEquals

/**
 * The real [LibraryScreen] over the real SQL page query (migrations applied by DriverFactory), so schema,
 * join and render regressions surface here rather than on a device. Desktop width, so the chip groups are inline.
 */
class LibraryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val db = SteleDatabase(driver)
    private val assets = SqlDelightAssetRepository(db)
    private val annotations = SqlDelightImageAnnotationRepository(db)
    private val library = SqlDelightLibraryRepository(db, driver)
    private var vm: LibraryViewModel? = null

    @After
    fun tearDown() {
        vm?.close()
        driver.close()
    }

    private suspend fun add(n: Int, name: String, type: AssetMediaType, at: Long, orphan: Boolean = false, annotated: Boolean = false) {
        val path = "/g/assets/$name"
        assets.saveAsset(
            AssetEntry(
                uuid = AssetUuid("asset-%06d".format(n)), filePath = path, relativePath = "../assets/$name", mediaType = type,
                subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
                pageUuids = emptyList(), sizeBytes = 2048L, importedAtMs = at, mlProcessed = false, mlAttemptedAt = null,
                mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
            ),
        )
        if (orphan) driver.execute(null, "UPDATE asset_index SET is_orphan = 1 WHERE uuid = ?", 1) { bindString(0, "asset-%06d".format(n)) }.await()
        if (annotated) {
            annotations.saveImageAnnotation(
                ImageAnnotation(uuid = "ann-%06d".format(n), blockUuid = "blk-$n", pageUuid = "page-1", graphPath = "/g", filePath = path, importedAtMs = 9_000L),
            )
        }
    }

    private val isAnnotatedCard = SemanticsMatcher("annotated card") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Annotated photo") } == true
    }

    private fun show(editor: MutableList<String> = mutableListOf(), detail: MutableList<String> = mutableListOf()): LibraryViewModel {
        val model = LibraryViewModel(library, searchDebounceMs = 10)
        vm = model
        composeTestRule.setContent {
            MaterialTheme { LibraryScreen(model, { editor += it }, { detail += it }) }
        }
        return model
    }

    private fun waitFor(text: String) = composeTestRule.waitUntil(10_000) {
        composeTestRule.onAllNodes(SemanticsMatcher("count line '$text'") {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } == text
        }).fetchSemanticsNodes().isNotEmpty()
    }

    private fun seed() = runBlocking {
        add(1, "kitchen.png", AssetMediaType.IMAGE, 1_000L, annotated = true)
        add(2, "sink.png", AssetMediaType.IMAGE, 1_001L)
        add(3, "permit.pdf", AssetMediaType.PDF, 1_002L, orphan = true)
        add(4, "memo.m4a", AssetMediaType.AUDIO, 1_003L)
    }

    @Test
    fun filtersAndSearch_workTogetherOverRealSql() {
        seed()
        show()
        waitFor("4 items")
        composeTestRule.onNodeWithTag("library-chip-annotated").performClick()
        waitFor("1 item")
        composeTestRule.onAllNodes(isAnnotatedCard).assertCountEquals(1)
        composeTestRule.onNodeWithTag("library-clear").performClick()
        waitFor("4 items")
        composeTestRule.onNodeWithTag("library-chip-orphaned").performClick()
        waitFor("1 item")
        composeTestRule.onNodeWithTag("library-clear").performClick()
        composeTestRule.onNodeWithTag("library-search").performTextInput("kit")
        waitFor("1 item")
    }

    @Test
    fun tapRule_annotatedImageOpensEditor_otherOpensDetail() {
        seed()
        val editor = mutableListOf<String>()
        val detail = mutableListOf<String>()
        show(editor, detail)
        waitFor("4 items")
        composeTestRule.onNodeWithTag("library-card-asset-000001").performClick()
        composeTestRule.onNodeWithTag("library-card-asset-000003").performClick()
        assertEquals(listOf("ann-000001"), editor)
        assertEquals(listOf("asset-000003"), detail)
    }

    @Test
    fun emptyLibrary_showsTheEmptyState() {
        show()
        composeTestRule.waitUntil(10_000) { composeTestRule.onAllNodes(hasTestTag("library-empty")).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithTag("library-empty").assertIsDisplayed()
        assertEquals("Nothing in your library yet", I18n.get(I18n.LIBRARY_EMPTY_TITLE))
    }
}
