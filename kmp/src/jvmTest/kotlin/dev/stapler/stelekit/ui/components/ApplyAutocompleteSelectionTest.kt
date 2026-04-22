package dev.stapler.stelekit.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlin.time.Clock
import org.junit.Test
import kotlin.test.assertEquals

class ApplyAutocompleteSelectionTest {

    private val now = Clock.System.now()
    private val cursorRect = Rect.Zero
    private val baseState = AutocompleteState("foo", cursorRect, AutocompleteTrigger.WIKI_LINK)

    private fun page(name: String) = Page(
        uuid = "00000000-0000-0000-0000-000000000001",
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `selectedIndex out of bounds does not crash and is a no-op`() {
        val searchResults = listOf<SearchResultItem>(SearchResultItem.PageItem(page("Alpha")))
        val tfv = TextFieldValue("[[foo", TextRange(5))
        var textChanged = false

        applyAutocompleteSelection(
            searchResults = searchResults,
            selectedIndex = 1, // out of bounds — only index 0 exists
            textFieldValue = tfv,
            onTextFieldValueChange = { textChanged = true },
            autocompleteState = baseState,
            onAutocompleteStateChange = {},
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals(false, textChanged, "should be a no-op when selectedIndex is out of bounds")
    }

    @Test
    fun `valid index applies wiki-link replacement`() {
        val searchResults = listOf<SearchResultItem>(SearchResultItem.PageItem(page("My Page")))
        val tfv = TextFieldValue("[[foo", TextRange(5))
        var newTfv: TextFieldValue? = null

        applyAutocompleteSelection(
            searchResults = searchResults,
            selectedIndex = 0,
            textFieldValue = tfv,
            onTextFieldValueChange = { newTfv = it },
            autocompleteState = baseState,
            onAutocompleteStateChange = {},
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals("[[My Page]]", newTfv?.text)
    }

    @Test
    fun `negative selectedIndex does not crash and is a no-op`() {
        val searchResults = listOf<SearchResultItem>(SearchResultItem.PageItem(page("Alpha")))
        val tfv = TextFieldValue("[[foo", TextRange(5))
        var textChanged = false

        applyAutocompleteSelection(
            searchResults = searchResults,
            selectedIndex = -1,
            textFieldValue = tfv,
            onTextFieldValueChange = { textChanged = true },
            autocompleteState = baseState,
            onAutocompleteStateChange = {},
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals(false, textChanged)
    }
}
