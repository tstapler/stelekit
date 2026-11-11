// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.Constraint
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Fixed
import dev.stapler.stelekit.fusion.Offset
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** What the post-sync job needs to fuse one project: its opaque slug, dimension registry and structural spec. */
data class ProjectFusionSetup(val slug: String, val registry: DimensionRegistry, val spec: FusionSpec) {
    fun stage() = ProjectFusionStage(slug, registry, spec)
}

/**
 * The registry and [FusionSpec] live in the project's synced `settings` (`registry`, `fusion`), so they travel in the sidecar
 * with the rest of the model. They hold dimension keys, labels and numbers only: no page title, path or file name.
 */
object ProjectFusionSettings {
    const val REGISTRY_KEY = "registry"
    const val FUSION_KEY = "fusion"

    /** Null while the project has no registry, or when either part does not decode; the job then writes nothing. */
    fun setupOf(slug: String, settings: JsonObject): ProjectFusionSetup? =
        (FusionSettingsLoader.load(settings) as? FusionSettingsState.Valid)?.let { ProjectFusionSetup(slug, it.registry, it.spec) }

    fun with(settings: JsonObject, registry: DimensionRegistry, spec: FusionSpec): JsonObject = JsonObject(
        settings + (REGISTRY_KEY to Json.parseToJsonElement(DimensionRegistry.toJson(registry))) + (FUSION_KEY to FusionSpecJson.encode(spec)),
    )
}

/** The first project's fusion setup (the job fuses one project per graph, like [readBoundSources]); null when there is none. */
suspend fun loadFusionSetup(repositories: SketchRepositories): ProjectFusionSetup? {
    val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return null
    return ProjectFusionSettings.setupOf(project.slug, project.settings)
}

/** Canonical JSON of a [FusionSpec]; decode is all-or-nothing, so a damaged spec never fuses a partial constraint set. */
object FusionSpecJson {
    const val SCHEMA = 1

    fun encode(spec: FusionSpec): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("constraints", JsonArray(spec.constraints.map(::constraint)))
        put("checks", JsonArray(spec.checks.map(::check)))
        put("zones", JsonArray(spec.zoneOrder.map(::JsonPrimitive)))
    }

    fun decode(element: JsonElement): FusionSpec? = try {
        val o = element.jsonObject
        require(o["schema"]?.jsonPrimitive?.int.let { it == null || it == SCHEMA }) { "unsupported schema" }
        FusionSpec(
            constraints = o["constraints"]?.jsonArray.orEmpty().map { decodeConstraint(it.jsonObject) },
            checks = o["checks"]?.jsonArray.orEmpty().map { decodeCheck(it.jsonObject) },
            zoneOrder = o["zones"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: NoSuchElementException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    private fun constraint(c: Constraint): JsonObject = buildJsonObject {
        put("id", c.id)
        when (c) {
            is Fixed -> { put("type", "fixed"); put("dimension", c.dimension); rational(c.value) }
            is Sum -> { put("type", "sum"); put("total", c.total); put("parts", JsonArray(c.parts.map(::JsonPrimitive))) }
            is Equal -> { put("type", "equal"); put("a", c.a); put("b", c.b) }
            is Offset -> { put("type", "offset"); put("a", c.a); put("b", c.b); rational(c.k) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.rational(r: Rational) {
        put("num", r.num)
        put("den", r.den)
    }

    private fun check(c: SumCheck): JsonObject = buildJsonObject {
        put("label", c.label)
        put("constraintId", c.constraintId)
        put("total", c.total)
        put("parts", JsonArray(c.parts.map(::JsonPrimitive)))
    }

    private fun decodeConstraint(o: JsonObject): Constraint {
        val id = o.getValue("id").jsonPrimitive.int
        return when (o.str("type")) {
            "fixed" -> Fixed(id, o.str("dimension"), o.rational())
            "sum" -> Sum(id, o.str("total"), o.strings("parts"))
            "equal" -> Equal(id, o.str("a"), o.str("b"))
            "offset" -> Offset(id, o.str("a"), o.str("b"), o.rational())
            else -> throw IllegalArgumentException("unknown constraint type")
        }
    }

    private fun decodeCheck(o: JsonObject) =
        SumCheck(o.str("label"), o.getValue("constraintId").jsonPrimitive.int, o.str("total"), o.strings("parts"))

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.contentOrNull ?: throw IllegalArgumentException(key)

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.rational(): Rational = Rational(getValue("num").jsonPrimitive.long, getValue("den").jsonPrimitive.long)
}
