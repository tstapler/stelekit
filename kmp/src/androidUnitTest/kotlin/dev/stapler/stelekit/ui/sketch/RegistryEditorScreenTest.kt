// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.projectbridge.ConstraintPreviewRow
import dev.stapler.stelekit.projectbridge.FusionSpec
import dev.stapler.stelekit.projectbridge.KeySuggestion
import dev.stapler.stelekit.projectbridge.PreviewVerdict
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RegistryEditorScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = RegistryEditorActions(
        onBack = { calls += "back" },
        onAddKey = { key, _, _ -> calls += "add:$key" },
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
            DimensionEntry("zone.a", DimensionKind.LENGTH, "Zone A"),
        ),
    )

    private fun show(state: RegistryEditorState) = rule.setContent { MaterialTheme { RegistryEditorContent(state, actions) } }

    private fun loaded(extra: RegistryEditorState.() -> RegistryEditorState = { this }) =
        RegistryEditorState(loaded = true, hasProject = true, registry = registry).extra()

    @Test
    fun withoutAProjectItSaysSo() {
        show(RegistryEditorState(loaded = true, hasProject = false))
        rule.onNodeWithText("Create a project first").assertIsDisplayed()
    }

    @Test
    fun keysShowTheirLabelKindAndAliasesAndOfferEdits() {
        val aliased = registry.copy(dimensions = registry.dimensions.map { if (it.key == "zone.a") it.copy(aliases = it.aliases.copy(yaml = listOf("zones.a.width"))) else it })
        show(loaded { copy(registry = aliased) })
        rule.onNodeWithText("Zone A (length)").assertIsDisplayed()
        rule.onNodeWithText("YAML path: zones.a.width").assertIsDisplayed()
        rule.onNodeWithText("Delete zone.a").performScrollTo().performClick()
        rule.onNodeWithText("Remove zones.a.width").performScrollTo().performClick()
        assertEquals(listOf("delete:zone.a", "unalias:zone.a:zones.a.width"), calls)
    }

    @Test
    fun suggestionsFromTheYamlAreOneTapToAdd() {
        show(loaded { copy(suggestions = listOf(KeySuggestion("niche.depth", "niche.depth", DimensionKind.LENGTH))) })
        rule.onNodeWithText("Suggested from the YAML file").assertIsDisplayed()
        rule.onNodeWithText("Add niche.depth").performClick()
        assertEquals(listOf("accept:niche.depth"), calls)
    }

    @Test
    fun constraintsShowTheSolverVerdictAndCanBeRemoved() {
        val spec = FusionSpec(listOf(Sum(1, "niche.width", listOf("zone.a"))))
        show(loaded { copy(spec = spec, preview = listOf(ConstraintPreviewRow(1, "niche.width = zone.a", PreviewVerdict.FAIL))) })
        rule.onNodeWithText("Constraints").performClick()
        rule.onNodeWithText("niche.width = zone.a").assertIsDisplayed()
        rule.onNodeWithText("FAIL").assertIsDisplayed()
        rule.onNodeWithText("Remove constraint 1").performClick()
        assertEquals(listOf("remove:1"), calls)
    }

    @Test
    fun damagedSettingsShowNeedsAttentionInsteadOfTheEditor() {
        show(loaded { copy(needsAttention = "The saved dimension registry could not be read") })
        rule.onNodeWithText("Needs attention").assertIsDisplayed()
        rule.onNodeWithText("Start over").performClick()
        assertEquals(listOf("startover"), calls)
    }
}
