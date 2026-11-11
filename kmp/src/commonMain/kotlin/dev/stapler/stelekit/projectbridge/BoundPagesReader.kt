// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.first

/**
 * What one read of the bound pages produced. [unloaded] counts index-only stubs and unreadable files: they contribute
 * nothing this run and the job retries on the next trigger. [missing] counts pages that no longer exist.
 */
data class SourceReadResult(
    val tablePages: List<TablePageText> = emptyList(),
    val prosePages: List<TablePageText> = emptyList(),
    val boundPages: List<BoundPageRef> = emptyList(),
    val unloaded: Int = 0,
    val missing: Int = 0,
    /** Synced bindings with no page on this device yet; the owner re-binds them. */
    val needsRebind: Int = 0,
    /** Current content hash per binding id, for the relevance check. */
    val contentHashes: Map<String, String> = emptyMap(),
) {
    /** Hands the bound pages to the collector. Table and prose pages share one list, as the collector runs both extractors over it. */
    fun toTexts(): ProjectSourceTexts = ProjectSourceTexts(pages = tablePages + prosePages, boundPages = boundPages)

    companion object {
        val EMPTY = SourceReadResult()
    }
}

/**
 * Reads bound wiki pages under the graph-read rules: uuid point lookups in chunks of [CHUNK] (never a scan), no writes.
 * [readText] reads a page's markdown file; a null result is treated as not yet readable.
 */
class BoundPagesReader(
    private val pages: PageRepository,
    private val readText: suspend (filePath: String) -> String?,
) {
    suspend fun read(resolutions: List<BindingResolution>): SourceReadResult {
        var result = SourceReadResult.EMPTY
        for (chunk in resolutions.chunked(CHUNK)) {
            for (r in chunk) {
                result = when (r) {
                    is BindingResolution.NeedsRebind -> result.copy(needsRebind = result.needsRebind + 1)
                    is BindingResolution.Resolved -> readOne(r.binding, r.pageUuid, result)
                }
            }
        }
        return result
    }

    private suspend fun readOne(binding: SourceBinding, pageUuid: String, acc: SourceReadResult): SourceReadResult {
        val page = pages.getPageByUuid(PageUuid(pageUuid)).first().getOrNull()
        val path = page?.filePath
        if (page == null || path == null) return acc.copy(missing = acc.missing + 1)
        val bound = acc.boundPages + BoundPageRef(page.name, path)
        if (!page.isContentLoaded) return acc.copy(boundPages = bound, unloaded = acc.unloaded + 1)
        val text = readText(path) ?: return acc.copy(boundPages = bound, unloaded = acc.unloaded + 1)
        val entry = TablePageText(locator = "page:${binding.bindingId}", markdown = text)
        val hashes = acc.contentHashes + (binding.bindingId to ContentHasher.sha256(text))
        return when (binding.kind) {
            SourceBindingKind.TABLE -> acc.copy(tablePages = acc.tablePages + entry, boundPages = bound, contentHashes = hashes)
            SourceBindingKind.PROSE -> acc.copy(prosePages = acc.prosePages + entry, boundPages = bound, contentHashes = hashes)
        }
    }

    companion object {
        const val CHUNK = 10
    }
}
