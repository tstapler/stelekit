package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StagedBlock(
    val uuid: String? = null,
    val content: String,
    val properties: Map<String, String> = emptyMap(),
    val children: List<StagedBlock> = emptyList(),
)

/**
 * JSON staging record of one source page (ADR-002 rev. 2). Markdown is never the transport:
 * it cannot carry uuids of blocks without `id::`, nor distinguish "no uuid" from "uuid".
 */
@Serializable
data class StagedPage(
    val version: Int = VERSION,
    val name: String,
    val isJournal: Boolean = false,
    val journalDate: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val blocks: List<StagedBlock> = emptyList(),
) {
    fun toMergePage(): MergePage = MergePage(
        name = name,
        isJournal = isJournal,
        journalDate = journalDate?.let(LocalDate::parse),
        properties = properties,
        blocks = blocks.map(StagedBlock::toMergeBlock),
    )

    companion object {
        const val VERSION = 1
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun from(page: MergePage) = StagedPage(
            name = page.name,
            isJournal = page.isJournal,
            journalDate = page.journalDate?.toString(),
            properties = page.properties,
            blocks = page.blocks.map(::stage),
        )

        fun encode(page: MergePage): String = json.encodeToString(serializer(), from(page))

        fun decode(text: String): Either<StagedPageError, MergePage> {
            val staged = try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                return StagedPageError.Malformed(e.message ?: "invalid staged page").left()
            }
            if (staged.version != VERSION) return StagedPageError.UnsupportedVersion(staged.version).left()
            return try {
                staged.toMergePage().right()
            } catch (e: IllegalArgumentException) {
                StagedPageError.Malformed(e.message ?: "invalid journalDate").left()
            }
        }
    }
}

private fun StagedBlock.toMergeBlock(): MergeBlock =
    MergeBlock(uuid, content, properties, children.map(StagedBlock::toMergeBlock))

private fun stage(b: MergeBlock): StagedBlock =
    StagedBlock(b.uuid, b.content, b.properties, b.children.map(::stage))

sealed interface StagedPageError {
    val message: String

    data class Malformed(override val message: String) : StagedPageError

    data class UnsupportedVersion(val version: Int) : StagedPageError {
        override val message: String = "Unsupported staged page version $version"
    }
}
