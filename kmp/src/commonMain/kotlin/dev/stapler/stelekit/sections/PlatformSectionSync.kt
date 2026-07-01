package dev.stapler.stelekit.sections

import dev.stapler.stelekit.repository.PageRepository

/**
 * Returns a platform-specific callback invoked after the section manifest loads.
 * WASM: seeds INDEX_ONLY stub pages for each section from the GitHub tree.
 * Other platforms: null (no-op).
 */
expect fun platformSectionSyncCallback(
    pageRepository: PageRepository,
): (suspend (SectionManifest, Map<String, SectionState>) -> Unit)?
