// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.util.ContentHasher

/**
 * The exact payload text that would leave the device (Stories 5.6b, 5.6c): slug, check verdicts,
 * registry labels, values and statuses, as compact lines. Opaque ids and source refs are left out of
 * the text because the question never needs them. The owner approves this text by its SHA-256, so any
 * change to a label or to this format changes the hash and asks again.
 */
object OutboundPreview {
    /** Leaves room under the 2k-token prompt budget for the system prompt and the question. */
    const val MAX_CHARS = 6000
    private const val OMITTED_RESERVE = 40

    fun payloadText(payload: LlmPayload): String {
        val head = buildList {
            add("Project: ${payload.project}")
            add("Checks:")
            payload.checks.forEach { add("- ${it.label}: ${it.verdict} ${it.summary}") }
        }
        val (attention, plain) = payload.dimensions.partition {
            it.status == PayloadStatus.CONFLICT || it.status == PayloadStatus.UNDER_CONSTRAINED
        }
        val lines = attention.map { "- ${it.label}: ${describe(it)}" }.let { if (it.isEmpty()) it else listOf("Needs attention:") + it } +
            listOf("Dimensions:") + plain.map { "- ${it.label}: ${describe(it)}" }

        val kept = ArrayList<String>()
        var used = head.sumOf { it.length + 1 }
        for (line in lines) {
            if (used + line.length + 1 + OMITTED_RESERVE > MAX_CHARS) break
            kept += line
            used += line.length + 1
        }
        val omitted = lines.size - kept.size
        return (head + kept + if (omitted > 0) listOf("- $omitted more omitted") else emptyList()).joinToString("\n", postfix = "\n")
    }

    fun hash(previewText: String): String = ContentHasher.sha256(previewText)

    private fun describe(d: PayloadDimension): String {
        val status = d.status.name.lowercase().replace('_', ' ')
        return if (d.display == null) status else "${d.display} ($status)"
    }
}
