// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Sum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FusionSettingsAndSuggestionsTest {
    private val yaml = """
        niche:
          width: 136
          note: tidy
        zones:
          - short: "a"
            width: 60
          - short: "b"
            width: 76
        flag: true
    """.trimIndent()

    private val empty = DimensionRegistry(dimensions = emptyList())

    @Test
    fun suggestions_should_OfferNumericPathsOnlyAndSkipMappedOnes() {
        val all = YamlKeySuggestions.suggest(yaml, empty)
        assertEquals(listOf("niche.width", "zones.a.width", "zones.b.width"), all.map { it.path })
        assertEquals(listOf("niche.width", "zones.a.width", "zones.b.width"), all.map { it.key })
        val registry = YamlKeySuggestions.accept(empty, all.first()).getOrNull()!!
        assertEquals(listOf("zones.a.width", "zones.b.width"), YamlKeySuggestions.suggest(yaml, registry).map { it.path })
        assertEquals(emptyList(), YamlKeySuggestions.suggest(null, empty))
        assertEquals(emptyList(), YamlKeySuggestions.suggest("a: [1, 2\n  b", empty))
    }

    @Test
    fun suggestedKeys_should_StayUniqueAndValid() {
        val messy = "Wall Width!: 12\nwall-width-: 13\n"
        val keys = YamlKeySuggestions.suggest(messy, empty).map { it.key }
        assertEquals(keys.distinct(), keys)
        var registry = empty
        YamlKeySuggestions.suggest(messy, empty).forEach { registry = YamlKeySuggestions.accept(registry, it).getOrNull()!! }
        assertEquals(keys.size, registry.dimensions.size)
    }

    @Test
    fun preview_should_ReportHoldFailAndUndetermined() {
        val registry = YamlKeySuggestions.suggest(yaml, empty).fold(empty) { acc, s -> YamlKeySuggestions.accept(acc, s).getOrNull()!! }
        val spec = ConstraintEditing.add(registry, FusionSpec(), ConstraintTemplate.PartsSum("niche.width", listOf("zones.a.width", "zones.b.width"), "Zones")).getOrNull()!!
        val holds = ConstraintPreview.rows(registry, spec, ConstraintPreview.yamlObservations(registry, yaml))
        assertEquals(PreviewVerdict.HOLD, holds.single().verdict)
        val fails = ConstraintPreview.rows(registry, spec, ConstraintPreview.yamlObservations(registry, yaml.replace("76", "77")))
        assertEquals(PreviewVerdict.FAIL, fails.single().verdict)
        assertEquals(PreviewVerdict.UNDETERMINED, ConstraintPreview.rows(registry, spec, emptyList()).single().verdict)
    }

    private fun settingsOf(registry: DimensionRegistry, spec: FusionSpec) = ProjectFusionSettings.with(JsonObject(emptyMap()), registry, spec)

    @Test
    fun loader_should_ClassifyAbsentValidAndDamagedSettings() {
        assertEquals(FusionSettingsState.Absent, FusionSettingsLoader.load(JsonObject(emptyMap())))
        val registry = YamlKeySuggestions.suggest(yaml, empty).fold(empty) { acc, s -> YamlKeySuggestions.accept(acc, s).getOrNull()!! }
        val spec = FusionSpec(listOf(Equal(1, "niche.width", "zones.a.width")))
        assertEquals(FusionSettingsState.Valid(registry, spec), FusionSettingsLoader.load(settingsOf(registry, spec)))

        val good = settingsOf(registry, spec)
        fun damaged(key: String, raw: String) = JsonObject(good + (key to Json.parseToJsonElement(raw)))
        assertEquals(FusionSettingsState.NeedsAttention(FusionSettingsLoader.BAD_REGISTRY), FusionSettingsLoader.load(damaged("registry", """{"schema":9,"dimensions":[]}""")))
        assertEquals(FusionSettingsState.NeedsAttention(FusionSettingsLoader.BAD_SPEC), FusionSettingsLoader.load(damaged("fusion", """{"constraints":[{"id":1,"type":"zzz"}]}""")))
        assertEquals(FusionSettingsState.NeedsAttention(FusionSettingsLoader.BAD_SPEC), FusionSettingsLoader.load(damaged("fusion", """{"schema":2}""")))
        val unknownKey = FusionSpec(listOf(Sum(1, "a", listOf("b")), Equal(1, "a", "b")))
        assertEquals(FusionSettingsState.NeedsAttention(FusionSettingsLoader.BAD_SPEC), FusionSettingsLoader.load(settingsOf(registry, unknownKey)))
        val specOnly = JsonObject(mapOf("fusion" to JsonPrimitive("x")))
        assertIs<FusionSettingsState.NeedsAttention>(FusionSettingsLoader.load(specOnly))
        assertNull(ProjectFusionSettings.setupOf("slug", specOnly))
    }
}
