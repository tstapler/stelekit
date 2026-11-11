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
    /** Current content hash per page uuid, for the relevance check. */
    val contentHashes: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = SourceReadResult()
    }
}

/**
 * Reads bound wiki pages under the graph-read rules: uuid point lookups in chunks of [CHUNK] (never a scan), no writes.
 * [readText] reads a page's markdown file; a null result is treated as not yet readable.
 */
class ProjectSourceReader(
    private val pages: PageRepository,
    private val readText: suspend (filePath: String) -> String?,
) {
    suspend fun read(bindings: List<SourceBinding>): SourceReadResult {
        var result = SourceReadResult.EMPTY
        for (chunk in bindings.chunked(CHUNK)) {
            for (binding in chunk) result = readOne(binding, result)
        }
        return result
    }

    private suspend fun readOne(binding: SourceBinding, acc: SourceReadResult): SourceReadResult {
        val page = pages.getPageByUuid(PageUuid(binding.pageUuid)).first().getOrNull()
        val path = page?.filePath
        if (page == null || path == null) return acc.copy(missing = acc.missing + 1)
        val bound = acc.boundPages + BoundPageRef(page.name, path)
        if (!page.isContentLoaded) return acc.copy(boundPages = bound, unloaded = acc.unloaded + 1)
        val text = readText(path) ?: return acc.copy(boundPages = bound, unloaded = acc.unloaded + 1)
        val entry = TablePageText(locator = "page:${binding.pageUuid}", markdown = text)
        val hashes = acc.contentHashes + (binding.pageUuid to ContentHasher.sha256(text))
        return when (binding.kind) {
            SourceBindingKind.TABLE -> acc.copy(tablePages = acc.tablePages + entry, boundPages = bound, contentHashes = hashes)
            SourceBindingKind.PROSE -> acc.copy(prosePages = acc.prosePages + entry, boundPages = bound, contentHashes = hashes)
        }
    }

    companion object {
        const val CHUNK = 10
    }
}
