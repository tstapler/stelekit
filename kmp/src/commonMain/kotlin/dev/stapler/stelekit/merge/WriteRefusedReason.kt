package dev.stapler.stelekit.merge

// Leaf types of DomainError.MergeError.WriteRefused; kept free of merge-module dependencies so `core` can hold them.

/** Why a writer refused; carried by [DomainError.MergeError.WriteRefused]. A refusal writes nothing. */
sealed interface WriteRefusedReason {
    val message: String

    data class PathOutsideGraph(val path: String) : WriteRefusedReason {
        override val message = "Path is outside the graph: $path"
    }

    data class InvalidPageName(val name: String) : WriteRefusedReason {
        override val message = "Invalid page name for a file path"
    }

    data class JournalFormatUnsupported(val format: String) : WriteRefusedReason {
        override val message = "Unsupported journal file-name format: $format"
    }

    data class NotRoundTrippable(val detail: dev.stapler.stelekit.merge.NotRoundTrippable) : WriteRefusedReason {
        override val message = detail.message
    }

    /** An owned-page replace found a page without the app's marker (or another digest's); it is never touched. */
    data class NotOwned(val page: String) : WriteRefusedReason {
        override val message = "Page '$page' exists and is not an app-generated page; not replacing it"
    }

    /** The owned page's body no longer matches its recorded hash: the user edited it. Surfaced for confirmation, never overwritten. */
    data class EditedByUser(val page: String) : WriteRefusedReason {
        override val message = "Page '$page' was edited since it was generated; not overwriting it"
    }

    /** The page file holds git conflict markers; resolve the sync conflict first. */
    data class Conflicted(val page: String) : WriteRefusedReason {
        override val message = "Page '$page' has unresolved sync conflict markers; resolve them first"
    }

    /** The target cannot be written off-graph (capability changed, e.g. a grant was lost mid-run). */
    data class Unwritable(val reason: WriteCapabilityReason) : WriteRefusedReason {
        override val message = reason.userText
    }
}

/**
 * Why a page cannot be safely rewritten off-graph. A `Left` of this type means the writer must
 * write nothing: share queues to the inbox, copy fails that page and keeps it staged for Retry.
 */
sealed interface NotRoundTrippable {
    val message: String

    /** The existing file does not parse. */
    data class ParseFailed(val detail: String) : NotRoundTrippable {
        override val message get() = "Existing file does not parse: $detail"
    }

    /** A fence, heading or prose continuation absorbed the appended bullet (known non-outline pages). */
    data class AppendAbsorbed(val detail: String) : NotRoundTrippable {
        override val message get() = "Appended block would be absorbed by existing content: $detail"
    }

    /** Adding the block would change a pre-existing block or the page properties. */
    data class ExistingContentChanged(val detail: String) : NotRoundTrippable {
        override val message get() = "Splice would change existing content: $detail"
    }

    /** An inserted block re-parses differently from what the merge intended (lossy markup). */
    data class InsertedContentChanged(val detail: String) : NotRoundTrippable {
        override val message get() = "Inserted content would not survive a re-read: $detail"
    }

    /** The page file is a symlink; replacing it would swap the link for a regular file and leave its target stale. */
    data class SymlinkTarget(val path: String) : NotRoundTrippable {
        override val message get() = "Page file is a symlink; not replacing it: $path"
    }

    /** Block references could not be resolved to a fixed point while merging. */
    data class RefsDidNotConverge(val passes: Int) : NotRoundTrippable {
        override val message get() = "Block references did not settle after $passes passes; the page was not copied"
    }

    /** The new block landed somewhere other than the last child of the intended parent. */
    data class Misplaced(val detail: String) : NotRoundTrippable {
        override val message get() = "Inserted block misplaced: $detail"
    }

    /** The splice altered original bytes instead of only adding lines (a splicer bug). */
    data class BytesChanged(val detail: String) : NotRoundTrippable {
        override val message get() = "Splice altered untouched bytes: $detail"
    }

    data class UnresolvableParent(val path: List<Int>) : NotRoundTrippable {
        override val message get() = "No block at path $path"
    }
}

enum class CapabilityAction(val label: String) {
    RegrantAccess("Re-grant access"),
    OpenGraph("Open that graph to copy into it"),
}

/** Why an INACTIVE target cannot be written off-graph. Rendered by the destination chooser as disabled-with-reason. */
sealed interface WriteCapabilityReason {
    val userText: String
    val action: CapabilityAction?

    data object Encrypted : WriteCapabilityReason {
        override val userText = "This graph is encrypted, so it can only be changed while it is open."
        override val action = CapabilityAction.OpenGraph
    }

    /** No verified write grant for the SAF folder (never granted, revoked, or unconfirmed). */
    data class NoGrant(val canRegrant: Boolean = true) : WriteCapabilityReason {
        override val userText = "SteleKit no longer has permission to write to this graph's folder."
        override val action = if (canRegrant) CapabilityAction.RegrantAccess else CapabilityAction.OpenGraph
    }

    /** Granted SAF folder, but atomic replace is unverified ([SAF_ATOMIC_REPLACE_VERIFIED]). */
    data object SafInboxOnly : WriteCapabilityReason {
        override val userText = "This graph's folder cannot be written safely while it is closed."
        override val action = CapabilityAction.OpenGraph
    }

    data object PlatformUnsupported : WriteCapabilityReason {
        override val userText = "This device cannot write to a graph that is not open."
        override val action = CapabilityAction.OpenGraph
    }
}

