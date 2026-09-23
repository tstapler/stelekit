package dev.stapler.stelekit.sections

import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.sync.WasmSectionSyncService

actual fun platformSectionSyncCallback(
    pageRepository: PageRepository,
): (suspend (SectionManifest, Map<String, SectionState>) -> Unit)? = { manifest, states ->
    val svc = WasmSectionSyncService(pageRepository)
    for (section in manifest.sections) {
        val state = states[section.id] ?: SectionState.ACTIVE
        // Sync ACTIVE and HIDDEN sections (content is on-device); skip REMOVED
        if (state != SectionState.REMOVED) {
            svc.syncSection(section)
        }
    }
}
