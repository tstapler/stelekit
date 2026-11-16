// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import kotlinx.serialization.json.JsonObject

/**
 * Every reason the importer refuses a file or drops/replaces a part of it. One code set serves both the
 * `Either.Left` of a refusal ([ImportError]) and the per-item entries of an [ImportReport].
 */
enum class ImportReason {
    // Whole-file refusals
    FileTooLarge,
    NotExcalidraw,
    MalformedJson,
    ImportTimeout,
    TooManyElements,
    LibraryTooLarge,
    Unexpected,

    // Span-level (a span is dropped, or the file refused when the span is a top-level one)
    ImportSpanTooLarge,
    ImportTooDeep,
    ElementsNotAnArray,
    FilesNotAnObject,

    // Element-level
    ElementNotAnObject,
    UnknownElementType,
    UnsupportedElement,
    DeletedElement,
    MissingId,
    DuplicateId,
    CyclicFrame,
    InvalidNumber,
    NonFiniteCoordinate,
    CoordinateOutOfRange,
    TooManyPoints,
    TextTruncated,

    // Image-level (the image becomes a labelled placeholder)
    ImageTooLarge,
    ImageMimeMismatch,
    ImageMimeNotAllowed,
    ImageInvalid,
    ImageBudgetExceeded,
    DataUrlEscaped,
    LibraryImage,

    // Library-level
    LibraryTooManyItems,
}

/** Why an import was refused. `cap` names the limit that was exceeded, when one was. */
data class ImportError(val reason: ImportReason, val detail: String = "", val cap: Long? = null)

/** One thing the importer did not take verbatim. `target` is an element id, file id, span key or item index. */
data class ImportIssue(val reason: ImportReason, val target: String? = null, val detail: String = "")

/** What the importer dropped, replaced or repaired; empty means the file imported untouched. */
data class ImportReport(
    val issues: List<ImportIssue> = emptyList(),
    val deletedCount: Int = 0,
    /** Counts of unknown element types by name (for example `stickynote`), skipped. */
    val unknownTypes: Map<String, Int> = emptyMap(),
    /** Counts of unsupported-but-kept kinds by name (`embeddable`, `iframe`, `magicframe`): OD-6 grey placeholders. */
    val unsupported: Map<String, Int> = emptyMap(),
) {
    val unsupportedCount: Int get() = unsupported.values.sum()

    fun count(reason: ImportReason): Int = issues.count { it.reason == reason }
}

/**
 * A sanitised Excalidraw element. Geometry and structure are typed and validated (finite, within `maxCoord`);
 * every member of the element, validated, stays in [raw] for the mapper (Story 5.1.2). `type` is one of the kinds
 * the mapper understands or, when [unsupported], one of `embeddable`/`iframe`/`magicframe` kept as a placeholder.
 */
data class ExcalidrawElement(
    val type: String,
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val angle: Double,
    val groupIds: List<String>,
    val frameId: String?,
    val index: String?,
    val locked: Boolean,
    val link: String?,
    val unsupported: Boolean,
    val raw: JsonObject,
)

/** An embedded image that passed header and budget checks; `bytes` are the decoded file bytes (not pixels). */
data class ExcalidrawFile(
    val id: String,
    val mimeType: String,
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val created: Long?,
) {
    override fun equals(other: Any?): Boolean = other is ExcalidrawFile && id == other.id && mimeType == other.mimeType &&
        bytes.contentEquals(other.bytes) && widthPx == other.widthPx && heightPx == other.heightPx && created == other.created

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}

/** The export-safe `appState` subset the importer keeps. */
data class ExcalidrawAppState(val gridSize: Double?, val viewBackgroundColor: String?)

/** `ImportedDataState`-like: everything optional on the way in, sanitised on the way out. */
data class ExcalidrawScene(
    val elements: List<ExcalidrawElement>,
    val files: List<ExcalidrawFile>,
    val appState: ExcalidrawAppState?,
    /** File ids whose image was refused; elements that reference them become placeholders. */
    val placeholderFileIds: Set<String>,
)

data class ImportResult(val scene: ExcalidrawScene, val report: ImportReport)

/** One `.excalidrawlib` item: a named group of sanitised elements; images inside are placeholders (no `files` in a library). */
data class ExcalidrawLibraryItem(val id: String, val name: String?, val elements: List<ExcalidrawElement>)

data class ImportedLibrary(val items: List<ExcalidrawLibraryItem>, val report: ImportReport)
