package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page

/**
 * Serializes a [Page] and its sorted [blocks] to a specific output format.
 *
 * [resolvedRefs] maps block UUIDs referenced via `((uuid))` syntax to their resolved text,
 * pre-fetched by [ExportService] before calling [export].
 */
interface PageExporter {
    val formatId: String
    val displayName: String

    fun export(
        page: Page,
        blocks: List<Block>,
        resolvedRefs: Map<String, String> = emptyMap()
    ): String
}
