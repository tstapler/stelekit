// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.random.Random

/** Opaque project slugs, `proj-<6 hex>`: never derived from the display name, so nothing outbound can leak it. */
object ProjectSlugs {
    private const val PREFIX = "proj-"
    private const val HEX = "0123456789abcdef"
    private val shape = Regex("proj-[0-9a-f]{6}")

    fun isValid(slug: String): Boolean = shape.matches(slug)

    /** Uses the low three bytes of [seed]; a slug depends on nothing but random input. */
    fun fromBits(seed: Int): String = buildString {
        append(PREFIX)
        for (shift in intArrayOf(20, 16, 12, 8, 4, 0)) append(HEX[(seed ushr shift) and 0xF])
    }

    fun generate(random: Random = Random.Default): String = fromBits(random.nextInt())

    /** Draws until [taken] no longer holds; the 24-bit space is large enough that this ends at once. */
    fun generateUnique(taken: (String) -> Boolean, random: Random = Random.Default): String {
        repeat(MAX_ATTEMPTS) { generate(random).let { if (!taken(it)) return it } }
        error("no free project slug after $MAX_ATTEMPTS attempts")
    }

    private const val MAX_ATTEMPTS = 64
}

/** Display-name and digest-title rules; both are validated, never silently altered beyond trimming. */
object ProjectNaming {
    const val MAX_NAME = 80
    const val MAX_TITLE = 120
    private val forbiddenInTitle = charArrayOf('[', ']', '#', '\\', '|')

    /** Trimmed and whitespace-collapsed; null when blank or too long. */
    fun normalizeName(raw: String): String? =
        raw.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

    /** A digest page title must be a plain, single-line page name. Null means invalid. */
    fun normalizeDigestTitle(raw: String): String? {
        val t = raw.trim()
        val valid = t.isNotEmpty() && t.length <= MAX_TITLE && t.none { it < ' ' || it in forbiddenInTitle }
        return t.takeIf { valid }
    }
}

object ProjectFactory {
    fun create(name: String, slug: String, uuid: ProjectUuid, createdAt: Long): SketchProject {
        require(ProjectSlugs.isValid(slug)) { "slug must be an opaque proj-xxxxxx" }
        return SketchProject(uuid = uuid, slug = slug, name = name, createdAt = createdAt)
    }

    fun newUuid(): ProjectUuid = ProjectUuid(UuidGenerator.generateV7())
}

/** What turning on `analyseAfterSync` needs next; every branch other than [Enable] sends nothing. */
sealed interface AnalyseToggleDecision {
    data object Disable : AnalyseToggleDecision
    data object NotOffered : AnalyseToggleDecision
    data class ShowPreview(val previewText: String) : AnalyseToggleDecision
    data object Enable : AnalyseToggleDecision
}

object AnalyseToggleRules {
    /**
     * Offered only with an explicit, acknowledged `PROJECT_ANALYSIS` provider; the first enable (and any enable after
     * the payload shape or labels changed) must pass through the outbound preview (Story 5.6c).
     */
    fun decide(enable: Boolean, providerConfigured: Boolean, previewText: String, previewAccepted: Boolean): AnalyseToggleDecision = when {
        !enable -> AnalyseToggleDecision.Disable
        !providerConfigured -> AnalyseToggleDecision.NotOffered
        previewAccepted -> AnalyseToggleDecision.Enable
        else -> AnalyseToggleDecision.ShowPreview(previewText)
    }

    /** The payload preview shown before any project data has been fused: slug only, so nothing but the opaque id would leave. */
    fun emptyPreview(slug: String): String = OutboundPreview.payloadText(LlmPayload(slug, emptyList(), emptyList()))
}

/** True when a project row exists; the post-sync job's `hasConfiguredProject` reads this (Story 5.9a). */
fun hasAnyProject(firstPage: List<SketchProject>): Boolean = firstPage.isNotEmpty()
