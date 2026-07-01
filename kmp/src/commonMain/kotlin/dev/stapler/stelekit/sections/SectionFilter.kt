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
        excludedPrefixes.any { prefix -> filePath.startsWithPathPrefix(prefix) }

    fun sectionIdForPath(filePath: String): String {
        for (section in manifest.sections) {
            if (filePath.startsWithPathPrefix(section.pagePathPrefix) ||
                filePath.startsWithPathPrefix(section.journalPathPrefix)) {
                return section.id
            }
        }
        return ""
    }

    /** Sections whose content is on-device (ACTIVE or HIDDEN); excludes REMOVED. */
    fun subscribedSectionIds(): Set<String> = buildSet {
        for (section in manifest.sections) {
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state != SectionState.REMOVED) add(section.id)
        }
    }

    val activeSectionIds: List<String>
        get() = manifest.sections.mapNotNull { section ->
            val state = sectionStates[section.id] ?: SectionState.ACTIVE
            if (state == SectionState.ACTIVE) section.id else null
        }
}

// Matches "…/pages/acme-work/foo.md" against prefix "pages/acme-work" but NOT "…/pages/acme-works/foo.md".
// Uses "/" boundaries so a prefix "docs" never matches a sibling directory "docs-archive/".
private fun String.startsWithPathPrefix(prefix: String): Boolean =
    this.contains("/$prefix/") || this.endsWith("/$prefix")
