// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.KeySuggestion
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class MapSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RegistryEditorDialogsTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = RegistryEditorActions(
        onBack = { calls += "back" },
        onAddKey = { key, kind, label -> calls += "add:$key:$kind:$label" },
        onRenameKey = { old, new -> calls += "rename:$old>$new" },
        onDeleteKey = { calls += "delete:$it" },
        onToggleKind = { key, kind -> calls += "kind:$key:$kind" },
        onAddAlias = { key, kind, alias -> calls += "alias:$key:$kind:$alias" },
        onRemoveAlias = { key, _, alias -> calls += "unalias:$key:$alias" },
        onAcceptSuggestion = { calls += "accept:$it" },
        onAddConstraint = { calls += "constraint:$it" },
        onRemoveConstraint = { calls += "remove:$it" },
        onStartOver = { calls += "startover" },
        onDismissMessage = { calls += "dismiss" },
    )
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("niche.width", DimensionKind.LENGTH, "Opening"),
            DimensionEntry("zone.a", DimensionKind.COUNT, "Zone A"),
        ),
    )

    private fun show(state: RegistryEditorState) = rule.setContent { MaterialTheme { RegistryEditorContent(state, actions) } }

    private fun loaded(extra: RegistryEditorState.() -> RegistryEditorState = { this }) =
        RegistryEditorState(loaded = true, hasProject = true, registry = registry).extra()

    private fun tap(text: String, index: Int = 0) = rule.onAllNodesWithText(text)[index].performScrollTo().performClick()

    private fun tapDialog(text: String, index: Int = 0) = rule.onAllNodesWithText(text)[index].performClick()

    @Test
    fun addKeyDialogCollectsKeyKindAndLabel() {
        show(loaded())
        tap("Add key")
        rule.onNodeWithText("Key").performTextReplacement("rod.height")
        rule.onNodeWithText("Label").performTextReplacement("Rod")
        tapDialog("Kind: length")
        tapDialog("Add")
        assertEquals(listOf("add:rod.height:COUNT:Rod"), calls)
    }

    @Test
    fun addKeyDialogCanBeCancelled() {
        show(loaded())
        tap("Add key")
        tapDialog("Cancel")
        assertTrue(calls.isEmpty())
        rule.onNodeWithText("Registry and constraints").assertIsDisplayed()
    }

    @Test
    fun renameDialogSendsTheNewKey() {
        show(loaded())
        tap("Rename")
        rule.onNodeWithText("New key").performTextReplacement("niche.span")
        tapDialog("Save")
        assertEquals(listOf("rename:niche.width>niche.span"), calls)
    }

    @Test
    fun aliasDialogPicksAKindAndSendsTheAlias() {
        show(loaded())
        tap("Add alias")
        tapDialog("Drawing label")
        rule.onNodeWithText("Drawing label (selected)").assertIsDisplayed()
        rule.onNodeWithText("Alias").performTextReplacement("Total Width")
        tapDialog("Add")
        assertEquals(listOf("alias:niche.width:SVG_LABEL:Total Width"), calls)
    }

    @Test
    fun kindToggleAndDeleteReachTheActions() {
        show(loaded())
        tap("Make count")
        tap("Make length")
        tap("Delete zone.a")
        assertEquals(listOf("kind:niche.width:COUNT", "kind:zone.a:LENGTH", "delete:zone.a"), calls)
    }

    @Test
    fun partsSumDialogBuildsTheTemplate() {
        show(loaded())
        tap("Constraints")
        tap("Parts sum to total")
        rule.onNodeWithText("Check name").performTextReplacement("Zones")
        rule.onNode(isToggleable()).performClick()
        tapDialog("Add")
        assertEquals(listOf("constraint:PartsSum(total=niche.width, parts=[zone.a], label=Zones)"), calls)
    }

    @Test
    fun equalDialogLetsYouPickBothKeys() {
        show(loaded())
        tap("Constraints")
        tap("Equal")
        tapDialog("First: niche.width")
        tapDialog("zone.a", 0)
        tapDialog("Second: zone.a")
        tapDialog("niche.width", 0)
        tapDialog("Add")
        assertEquals(listOf("constraint:Equality(a=zone.a, b=niche.width)"), calls)
    }

    @Test
    fun offsetDialogTakesTheOffsetText() {
        show(loaded())
        tap("Constraints")
        tap("Offset")
        rule.onNodeWithText("First minus second").performTextReplacement("3/4")
        tapDialog("Add")
        assertEquals(listOf("constraint:OffsetBy(a=niche.width, b=zone.a, offsetText=3/4)"), calls)
    }

    @Test
    fun constraintDialogCancelsAndTabsSwitchBack() {
        show(loaded())
        tap("Constraints")
        rule.onNodeWithText("No constraints yet").assertIsDisplayed()
        tap("Equal")
        tapDialog("Cancel")
        tap("Registry")
        rule.onNodeWithText("Add key").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun stateBannersAndEmptyStatesShow() {
        show(RegistryEditorState())
        rule.onNodeWithText("Loading").assertIsDisplayed()
    }

    @Test
    fun emptyRegistryAndMessageAndBackWork() {
        show(RegistryEditorState(loaded = true, hasProject = true, message = "Nope"))
        rule.onNodeWithText("No dimension keys yet").assertIsDisplayed()
        rule.onNodeWithText("Nope").assertIsDisplayed()
        tap("Dismiss")
        tap("Back")
        assertEquals(listOf("dismiss", "back"), calls)
    }

    @Test
    fun longSuggestionListIsTruncatedWithACount() {
        val many = (1..15).map { KeySuggestion("p.$it", "p.$it", DimensionKind.LENGTH) }
        show(loaded { copy(suggestions = many) })
        rule.onNodeWithText("3 more after you add these").assertExists()
    }

    @Test
    fun statefulScreenSavesAKeyThroughTheViewModel() {
        val repos = inMemorySketchRepositories()
        runBlocking { repos.projects.saveProject(ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L)) }
        val vm = RegistryEditorViewModel(repos.projects, { it() }, { "niche:\n  width: 136\n" }, UnconfinedTestDispatcher())
        var backed = false
        rule.setContent { MaterialTheme { RegistryEditorScreen(vm, onBack = { backed = true }) } }
        rule.onNodeWithText("Add niche.width").performClick()
        rule.waitUntil(5_000) { vm.state.value.registry.dimensions.isNotEmpty() }
        rule.onNodeWithText("niche.width").assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        assertTrue(backed)
    }

    @Test
    fun projectListOpensTheEditorAndComesBack() {
        val repos = inMemorySketchRepositories()
        runBlocking { repos.projects.saveProject(ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L)) }
        val settings = MapSettings()
        val list = ProjectListViewModel(
            repos.projects, "g1", ProjectBridgeSettings(settings), LlmSettings(settings), { it() }, { 1L }, Random(1),
            dispatcher = UnconfinedTestDispatcher(),
        )
        rule.setContent {
            MaterialTheme {
                ProjectListScreen(
                    list, onOpenProject = {},
                    registryEditor = { RegistryEditorViewModel(repos.projects, { it() }, { null }, UnconfinedTestDispatcher()) },
                )
            }
        }
        rule.onNodeWithText("Registry and constraints").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("No dimension keys yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Projects").assertIsDisplayed()
    }
}
