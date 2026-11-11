// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PayloadStatus { CONFIRMED, SINGLE_SOURCE, CONFLICT, DERIVABLE, NOT_REPRESENTABLE, UNDER_CONSTRAINED }

/** [ref] is `<kind>:<hash>`; the locator it stands for stays in the device-local RefTable. */
@Serializable
data class PayloadSource(val kind: String, val ref: String)

@Serializable
data class PayloadCheck(val label: String, val verdict: String, val summary: String)

/** [label] is the registry label, the only free text in the payload. */
@Serializable
data class PayloadDimension(
    val id: String,
    val label: String,
    val status: PayloadStatus,
    val display: String? = null,
    val num: Long? = null,
    val den: Long? = null,
    val sources: List<PayloadSource> = emptyList(),
)

@Serializable
data class LlmPayload(val project: String, val checks: List<PayloadCheck>, val dimensions: List<PayloadDimension>)

/**
 * Projects a fused project onto a CLOSED vocabulary for the LLM step (Story 5.5b): the opaque slug,
 * opaque dimension ids, registry labels, values, statuses and opaque source refs. No page title,
 * address or filename is an input, so none can be an output.
 */
object LlmPayloadBuilder {
    private val refShape = Regex("[a-z0-9]{6,16}")
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    fun build(
        slug: String,
        result: FusionResult,
        registry: DimensionRegistry,
        observations: List<Observation>,
        checks: List<SumCheck>,
    ): LlmPayload {
        fun kindOf(key: String) = registry.entry(key)?.kind ?: DimensionKind.LENGTH
        val byDimension = observations.filterNot { it.source.origin.carriesDigestMarker }.groupBy { it.dimension }
        val dimensions = result.dimensions.map { (key, status) ->
            val id = opaqueId(key)
            val kind = kindOf(key)
            val sources = byDimension[key].orEmpty()
                .map { payloadSource(it.source.kind, it.source.ref.id) }.distinct().sortedWith(compareBy({ it.kind }, { it.ref }))
            val label = registry.entry(key)?.label ?: id
            val value = DimensionDisplay.single(status)
            val display = when (status) {
                is DimensionStatus.Conflict -> status.pairs.flatMap { listOf(it.first.value, it.second.value) }.distinct()
                    .joinToString(" vs ") { DimensionDisplay.value(it, kind) }
                else -> value?.let { DimensionDisplay.value(it, kind) }
            }
            PayloadDimension(id, label, statusOf(status), display, value?.num, value?.den, sources)
        }.sortedBy { it.id }
        return LlmPayload(
            project = slug,
            checks = checks.map { DimensionDisplay.evaluate(it, result, ::kindOf) }.map { PayloadCheck(it.label, it.verdict.name, it.summary) },
            dimensions = dimensions,
        )
    }

    fun toJson(payload: LlmPayload): String = json.encodeToString(LlmPayload.serializer(), payload) + "\n"

    private fun opaqueId(key: String) = "d-" + ContentHasher.sha256("dimension:$key").take(8)

    /** A ref already in the gate's shape passes through; anything else is re-hashed rather than trusted. */
    private fun payloadSource(kind: SourceKind, id: String): PayloadSource {
        val prefix = when (kind) {
            SourceKind.TABLE -> "table"
            SourceKind.YAML -> "yaml"
            SourceKind.SVG_LABEL, SourceKind.SVG_GEOMETRY -> "svg"
            SourceKind.PROSE -> "prose"
            SourceKind.DERIVED -> "derived"
        }
        val safe = if (refShape.matches(id)) id else ContentHasher.sha256("$prefix:$id").take(12)
        return PayloadSource(prefix, "$prefix:$safe")
    }

    private fun statusOf(status: DimensionStatus) = when (status) {
        is DimensionStatus.Confirmed -> PayloadStatus.CONFIRMED
        is DimensionStatus.SingleSource -> PayloadStatus.SINGLE_SOURCE
        is DimensionStatus.Conflict -> PayloadStatus.CONFLICT
        is DimensionStatus.Derivable -> PayloadStatus.DERIVABLE
        is DimensionStatus.NotRepresentableValue -> PayloadStatus.NOT_REPRESENTABLE
        DimensionStatus.UnderConstrained -> PayloadStatus.UNDER_CONSTRAINED
    }
}
