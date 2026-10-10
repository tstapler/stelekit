package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Behavioral tests for the typed lazy list wrappers.
 *
 * These verify that the wrappers correctly route items to their content lambdas and that
 * key/contentType/index delegation works as expected. The compile-time type guarantee
 * (String key enforcement) is verified at build time; these tests cover the runtime behavior.
 */
class TypedLazyItemsTest {

    @get:Rule
    val rule = createComposeRule()

    // ── typedItems ────────────────────────────────────────────────────────────────

    @Test
    fun `typedItems renders all items in the list`() {
        val items = listOf("Alpha", "Beta", "Gamma")
        rule.setContent {
            LazyColumn {
                typedItems(items, key = { it }) { item ->
                    Text(item)
                }
            }
        }
        items.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `typedItems key lambda receives each item in list order`() {
        val items = listOf("a", "b", "c")
        val capturedKeys = mutableListOf<String>()
        rule.setContent {
            LazyColumn {
                typedItems(items, key = { it.also { k -> capturedKeys += k } }) { Text(it) }
            }
        }
        rule.waitForIdle()
        assertEquals(items, capturedKeys)
    }

    @Test
    fun `typedItems with contentType still renders all items correctly`() {
        // contentType is a layout hint that Compose may not invoke eagerly for all items;
        // this test verifies that providing a non-null contentType doesn't break rendering.
        val items = listOf("x", "y", "z")
        rule.setContent {
            LazyColumn {
                typedItems(
                    items = items,
                    key = { it },
                    contentType = { "item-type" },
                ) { Text(it) }
            }
        }
        items.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    // ── typedItemsIndexed ─────────────────────────────────────────────────────────

    @Test
    fun `typedItemsIndexed renders all items`() {
        val items = listOf("One", "Two", "Three")
        rule.setContent {
            LazyColumn {
                typedItemsIndexed(items, key = { i, item -> "$i:$item" }) { _, item ->
                    Text(item)
                }
            }
        }
        items.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `typedItemsIndexed key lambda receives correct index and item`() {
        val items = listOf("A", "B", "C")
        val capturedPairs = mutableListOf<Pair<Int, String>>()
        rule.setContent {
            LazyColumn {
                typedItemsIndexed(
                    items = items,
                    key = { i, item -> "$i:$item".also { capturedPairs += i to item } },
                ) { _, item -> Text(item) }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf(0 to "A", 1 to "B", 2 to "C"), capturedPairs)
    }

    @Test
    fun `typedItemsIndexed itemContent lambda receives correct index and item`() {
        val items = listOf("P", "Q", "R")
        val rendered = mutableListOf<String>()
        rule.setContent {
            LazyColumn {
                typedItemsIndexed(items, key = { i, item -> "$i:$item" }) { index, item ->
                    rendered += "$index:$item"
                    Text("$index:$item")
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("0:P", "1:Q", "2:R"), rendered)
    }

    // ── typedGridItems ────────────────────────────────────────────────────────────

    @Test
    fun `typedGridItems renders all items in a grid`() {
        val items = listOf("Img1", "Img2", "Img3", "Img4")
        rule.setContent {
            LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                typedGridItems(items, key = { it }) { item ->
                    Text(item)
                }
            }
        }
        items.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `typedGridItems key lambda receives each item`() {
        val items = listOf("img-a", "img-b")
        val capturedKeys = mutableListOf<String>()
        rule.setContent {
            LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                typedGridItems(items, key = { it.also { k -> capturedKeys += k } }) { Text(it) }
            }
        }
        rule.waitForIdle()
        assertEquals(items, capturedKeys)
    }

    // ── typedGridItemsIndexed ─────────────────────────────────────────────────────

    @Test
    fun `typedGridItemsIndexed renders all items`() {
        val items = listOf("S1", "S2", "S3")
        rule.setContent {
            LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                typedGridItemsIndexed(items, key = { i, item -> "$i:$item" }) { _, item ->
                    Text(item)
                }
            }
        }
        items.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `typedGridItemsIndexed key lambda receives correct index and item`() {
        val items = listOf("X", "Y")
        val capturedPairs = mutableListOf<Pair<Int, String>>()
        rule.setContent {
            LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                typedGridItemsIndexed(
                    items = items,
                    key = { i, item -> "$i:$item".also { capturedPairs += i to item } },
                ) { _, item -> Text(item) }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf(0 to "X", 1 to "Y"), capturedPairs)
    }
}
