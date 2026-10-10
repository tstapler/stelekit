package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline

/** Unique id of one copy run (staging dir, manifest, log correlation). */
@JvmInline
value class MergeId(val value: String)

/** Property keys written by the merge itself. */
object MergePropertyKeys {
    const val SRC_ID = "src-id"
    const val CONFLICT = "merge-conflict"
    const val CONFLICT_SOURCE = "merge-source"
}

/**
 * `src-id::` value `<sourceGraphId>:<sourceUuid>`. Source uuids never contain ':',
 * so the last ':' is the separator even if a graph id did.
 */
@JvmInline
value class SourceBlockRef(val value: String) {
    val sourceGraphId: String get() = value.substringBeforeLast(':')
    val sourceUuid: String get() = value.substringAfterLast(':')

    companion object {
        fun of(sourceGraphId: GraphId, sourceUuid: String) = SourceBlockRef("${sourceGraphId.value}:$sourceUuid")
    }
}

/**
 * IO-free block node. A null [uuid] means "no explicit id::" (its parsed uuid is positional),
 * which the append-placement rule depends on; converters must preserve that distinction.
 */
data class MergeBlock(
    val uuid: String?,
    val content: String,
    val properties: Map<String, String> = emptyMap(),
    val children: List<MergeBlock> = emptyList(),
)

data class MergePage(
    val name: String,
    val isJournal: Boolean = false,
    val journalDate: LocalDate? = null,
    val properties: Map<String, String> = emptyMap(),
    val blocks: List<MergeBlock> = emptyList(),
)

/**
 * [blockKey] is injectable so tests run exact-trimmed and normalized matching.
 * [shortContentMinLength] is carried for ADR-002 parity: the parent guard for short
 * content is structural (matching is sibling-scoped under an already-matched parent).
 */
data class MergePolicy(
    val sourceGraphId: GraphId,
    val sourceGraphName: String,
    val blockKey: (String) -> String = ::normalizeBlockContent,
    val shortContentMinLength: Int = 4,
)

fun normalizeBlockContent(content: String): String = content.trim().replace(Regex("\\s+"), " ")

fun exactTrimmedBlockContent(content: String): String = content.trim()

/** Incoming block that collided with an existing one and was kept as a flagged sibling. */
data class BlockConflict(
    val targetUuid: String?,
    val incomingUuid: String,
    val pageName: String,
)

sealed interface MergeOutcome {
    data class New(val page: MergePage) : MergeOutcome

    data object Unchanged : MergeOutcome

    /** [added] counts inserted blocks including descendants, excluding conflict siblings. */
    data class Merged(
        val page: MergePage,
        val added: Int,
        val conflicts: List<BlockConflict>,
        val propertyClashes: List<String> = emptyList(),
    ) : MergeOutcome
}
