// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import kotlin.time.TimeSource

/** What one discovery pass found: [found] hold a v2 sheet; [deferred] projects were left for the next open by a bound. */
data class DiscoveryResult(val found: List<String>, val scanned: Int, val deferred: Int)

/**
 * The bounded, read-only scan that finds a synced v2 project the web database does not know while the classic file
 * side is `Off` (D-153; the `v2-discovery` block of requirements.md). For each unknown project it lists `sheets/` and
 * reads the first [PROBE_BYTES] bytes of ONE `*.v2.json` file through [SheetCodec.classifyHeader]; it opens no classic
 * sheet and no `project-model.json`, and it never writes to the sidecar. Past a bound the rest waits for the next open.
 *
 * [elapsedMs] is injected so the time budget is testable without a real clock.
 */
class SyncedV2ProjectDiscovery(
    private val store: ProjectSidecarStore,
    private val elapsedMs: () -> Long = monotonicElapsedMs(),
) {
    suspend fun scan(unknownSlugs: List<String>): DiscoveryResult {
        val candidates = unknownSlugs.take(MAX_UNKNOWN_PROJECTS)
        val found = mutableListOf<String>()
        var scanned = 0
        for (slug in candidates) {
            if (elapsedMs() >= TIME_BUDGET_MS) break
            scanned++
            if (holdsV2Sheet(slug)) found += slug
        }
        return DiscoveryResult(found, scanned, deferred = unknownSlugs.size - scanned)
    }

    private suspend fun holdsV2Sheet(slug: String): Boolean {
        val file = store.listSheetFiles(slug).firstOrNull { it.endsWith(ClassicWebFileSidePolicy.V2_SUFFIX) } ?: return false
        val prefix = store.readSheetPrefix(slug, "${SheetPaths.SHEETS_DIR}/$file", PROBE_BYTES) ?: return false
        return SheetCodec.classifyHeader(prefix) == HeaderVerdict.V2
    }

    companion object {
        /** Parameters of the `v2-discovery` block of requirements.md; not scene caps, so they are not `SceneLimits` fields. */
        const val MAX_UNKNOWN_PROJECTS = 200
        const val PROBE_BYTES = SheetCodec.HEADER_PROBE_BYTES
        const val TIME_BUDGET_MS = 500L

        private fun monotonicElapsedMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        /** A decoded synced bundle reduced to its v2 sheets; classic sheets and their primitives stay out of the database. */
        fun v2SheetsOnly(bundle: ProjectBundle): ProjectBundle {
            val v2 = bundle.sheets.filter { it.schemaVersion == SheetPaths.SCHEMA_V2 }
            val uuids = v2.map { it.uuid }.toSet()
            return bundle.copy(
                sheets = v2,
                primitives = bundle.primitives.filter { it.sheetUuid in uuids },
                sheetPayloads = bundle.sheetPayloads.filterKeys { it in uuids },
            )
        }
    }
}
