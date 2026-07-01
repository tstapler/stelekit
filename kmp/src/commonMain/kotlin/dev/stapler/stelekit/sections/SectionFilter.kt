package dev.stapler.stelekit.sections

/**
 * Encapsulates the file-path exclusion predicate for section-aware graph loading.
 * A null [SectionFilter] means "no sections" — all files are included (backward compatible).
 *
 * [excludes] returns true if the given file path should be excluded from loading
 * (i.e., it belongs to a section that is HIDDEN or REMOVED).
 */
class SectionFilter(
    val manifest: SectionManifest,
    val sectionStates: Map<String, SectionState>,
) {
    private val excludedPrefixes: Set<String> = buildSet {
        for (section in manifest.sections) {
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state == SectionState.HIDDEN || state == SectionState.REMOVED) {
                add(section.pagePathPrefix)
                add(section.journalPathPrefix)
            }
        }
    }

    fun excludes(filePath: String): Boolean =
        excludedPrefixes.any { prefix -> filePath.contains(prefix) }

    fun sectionIdForPath(filePath: String): String {
        for (section in manifest.sections) {
            if (filePath.contains(section.pagePathPrefix) || filePath.contains(section.journalPathPrefix)) {
                return section.id
            }
        }
        return ""
    }

    fun subscribedSectionIds(): Set<String> = buildSet {
        for (section in manifest.sections) {
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state != SectionState.HIDDEN && state != SectionState.REMOVED) add(section.id)
        }
    }

    val activeSectionIds: List<String>
        get() = manifest.sections.mapNotNull { section ->
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state == SectionState.ACTIVE) section.id else null
        }
}
