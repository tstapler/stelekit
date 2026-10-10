package dev.stapler.stelekit.sections

import dev.stapler.stelekit.repository.PageRepository

actual fun platformSectionSyncCallback(
    pageRepository: PageRepository,
): (suspend (SectionManifest, Map<String, SectionState>) -> Unit)? = null
