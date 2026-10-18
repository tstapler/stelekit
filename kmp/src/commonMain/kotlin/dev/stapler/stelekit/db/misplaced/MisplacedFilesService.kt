// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.misplaced

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.db.ShareWritePathRepair
import dev.stapler.stelekit.db.StrayResult
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.util.FileUtils
import kotlinx.coroutines.withContext

/** A journal or page that sits outside the configured notes folder but might belong inside it. */
data class MisplacedFile(
    /** `journals/<name>.md` or `pages/<name>.md`, relative to the repo root. */
    val relativePath: String,
    val kind: Kind,
    /** The page name as the user knows it. */
    val name: String,
    /** True when the notes folder already has a page with this file name, so moving means merging. */
    val hasCopyInNotes: Boolean,
    internal val fingerprint: String,
) {
    enum class Kind { JOURNAL, PAGE }
}

/** Remembers which misplaced files the user chose to leave alone. */
interface MisplacedIgnoreStore {
    fun load(): Set<String>
    fun save(entries: Set<String>)
}

class SettingsMisplacedIgnoreStore(private val settings: Settings, graphId: GraphId) : MisplacedIgnoreStore {
    private val key = "misplaced_ignored_${graphId.value}"

    override fun load(): Set<String> = settings.getString(key, "").lineSequence().filter { it.isNotBlank() }.toSet()

    override fun save(entries: Set<String>) = settings.putString(key, entries.sorted().joinToString("\n"))
}

/**
 * Finds journals and pages outside the notes folder and lets the user move them in (three-way merging into
 * an existing copy) or leave them alone. Nothing here moves a file by itself: [scan] only reports, and [move]
 * runs only when the user chose it. An ignore holds until the file changes, so an edited stray file asks again.
 */
class MisplacedFilesService(
    private val fs: FileSystem,
    private val ignoreStore: MisplacedIgnoreStore,
    /** Called once with every `old path -> new path` a batch moved, to repoint page rows. */
    private val repointPages: suspend (Map<String, String>) -> Unit = {},
    private val repair: ShareWritePathRepair = ShareWritePathRepair(fs),
) {
    suspend fun scan(repoRoot: RepoRoot, notesPath: EffectiveNotesPath): List<MisplacedFile> = withContext(PlatformDispatcher.IO) {
        if (!ShareWritePathRepair.isSubdirLayout(repoRoot, notesPath)) return@withContext emptyList()
        val root = repoRoot.value.trimEnd('/')
        val notes = notesPath.value.trimEnd('/')
        val ignored = ignoreStore.load()
        FOLDERS.flatMap { (folder, kind) ->
            if (!fs.directoryExists("$root/$folder")) return@flatMap emptyList()
            fs.listFiles("$root/$folder").map { it.substringAfterLast('/') }
                .filter { it.endsWith(MARKDOWN_SUFFIX) && ShareWritePathRepair.isStrayRelativePath("$folder/$it") }
                .sorted()
                .map { fileName ->
                    val relative = "$folder/$fileName"
                    MisplacedFile(
                        relativePath = relative,
                        kind = kind,
                        name = FileUtils.decodeFileName(fileName.removeSuffix(MARKDOWN_SUFFIX)),
                        hasCopyInNotes = fs.fileExists("$notes/$relative"),
                        fingerprint = fingerprint("$root/$relative"),
                    )
                }
                .filter { entry(it) !in ignored }
        }
    }

    /** What moving [file] would do, for showing before the user chooses. */
    suspend fun preview(repoRoot: RepoRoot, notesPath: EffectiveNotesPath, file: MisplacedFile): ProposedResolution =
        withContext(PlatformDispatcher.IO) {
            try {
                repair.previewOne(repoRoot, notesPath, file.relativePath)
            } catch (e: UnsupportedOperationException) {
                ProposedResolution.CannotMerge("this device cannot read the file's contents")
            }
        }

    /**
     * Moves each of [files] into the notes folder, merging it into the existing copy when there is one, then
     * repoints the page rows of everything that moved. One file failing never stops the rest.
     */
    suspend fun moveAll(repoRoot: RepoRoot, notesPath: EffectiveNotesPath, files: List<MisplacedFile>): List<Pair<MisplacedFile, StrayResult>> =
        withContext(PlatformDispatcher.IO) {
            val results = files.map { file ->
                file to try {
                    repair.settleOne(repoRoot, notesPath, file.relativePath)
                } catch (e: UnsupportedOperationException) {
                    StrayResult.Failed("this device cannot read the file's contents")
                }
            }
            val root = repoRoot.value.trimEnd('/')
            val notes = notesPath.value.trimEnd('/')
            repointPages(
                results.filter { (_, result) -> result is StrayResult.Done }
                    .associate { (file, _) -> "$root/${file.relativePath}" to "$notes/${file.relativePath}" },
            )
            results
        }

    /** Leaves [files] where they are and stops asking about them until they change. */
    suspend fun ignore(files: Collection<MisplacedFile>) = withContext(PlatformDispatcher.IO) {
        ignoreStore.save(ignoreStore.load() + files.map(::entry))
    }

    private fun entry(file: MisplacedFile) = "${file.relativePath}\t${file.fingerprint}"

    private fun fingerprint(path: String): String = "${fs.getFileSize(path) ?: 0L}:${fs.getLastModifiedTime(path) ?: 0L}"

    private companion object {
        const val MARKDOWN_SUFFIX = ".md"
        val FOLDERS = listOf("journals" to MisplacedFile.Kind.JOURNAL, "pages" to MisplacedFile.Kind.PAGE)
    }
}
