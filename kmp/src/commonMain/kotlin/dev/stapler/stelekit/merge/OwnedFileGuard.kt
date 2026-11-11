package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.ConflictMarkerDetector

/**
 * Decides whether an owned page may be created or replaced. Pure; the caller evaluates it under
 * `GraphWriteLock(target)` in the same block as the replace (no TOCTOU).
 * Order: conflict markers in the FILE (the DB alone looks clean), marker on block 0, hash compare.
 */
object OwnedFileGuard {
    sealed interface Decision {
        data object Create : Decision
        data object Unchanged : Decision
        data object Replace : Decision
        data class Refuse(val reason: WriteRefusedReason) : Decision
    }

    /**
     * @param fileText the page file's text, or null when there is no file
     * @param existing the page as stored, or null when it has neither blocks nor a non-blank file
     * @param expectedPriorHash the [OwnedHash] the caller last wrote; null means "expected no page"
     */
    fun decide(
        pageName: String,
        fileText: String?,
        existing: MergePage?,
        incoming: MergePage,
        expectedPriorHash: String?,
    ): Decision {
        if (fileText != null && ConflictMarkerDetector.hasConflictMarkers(fileText)) {
            return Decision.Refuse(WriteRefusedReason.Conflicted(pageName))
        }
        invalidIncoming(incoming)?.let {
            return Decision.Refuse(WriteRefusedReason.NotRoundTrippable(NotRoundTrippable.InsertedContentChanged(it)))
        }
        if (existing == null) return Decision.Create
        val marker = OwnedMarker.of(existing) ?: return Decision.Refuse(WriteRefusedReason.NotOwned(pageName))
        val newMarker = checkNotNull(OwnedMarker.of(incoming))
        if (OwnedMarker.slug(marker) != OwnedMarker.slug(newMarker)) {
            return Decision.Refuse(WriteRefusedReason.NotOwned(pageName))
        }
        val oldHash = OwnedHash.of(existing)
        if (marker.properties[OwnedMarker.HASH] != oldHash || (expectedPriorHash != null && expectedPriorHash != oldHash)) {
            return Decision.Refuse(WriteRefusedReason.EditedByUser(pageName))
        }
        val same = OwnedHash.of(incoming) == oldHash && newMarker.content == marker.content &&
            newMarker.properties.filterKeys { it != "id" } == marker.properties.filterKeys { it != "id" }
        return if (same) Decision.Unchanged else Decision.Replace
    }

    /** The new content must carry the marker on a visible first block, a slug and a fresh hash. */
    private fun invalidIncoming(incoming: MergePage): String? {
        val marker = OwnedMarker.of(incoming) ?: return "owned page has no marker on its first block"
        return when {
            marker.content.isBlank() -> "marker block has no visible text"
            OwnedMarker.slug(marker) == null -> "marker has no ${OwnedMarker.DIGEST}"
            marker.properties[OwnedMarker.HASH] != OwnedHash.of(incoming) -> "marker ${OwnedMarker.HASH} is not the body's OwnedHash"
            else -> null
        }
    }
}
