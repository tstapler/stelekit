package dev.stapler.stelekit.model

import dev.stapler.stelekit.diagnostics.DirectoryScanResult
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Type-safe wrapper for graph identifiers (sha256(canonicalPath).take(16)). */
@Serializable
@JvmInline
value class GraphId(val value: String)

/** Fixed GraphId for the demo graph — not a sha256 path hash; stable and collision-proof. */
val DEMO_GRAPH_ID = GraphId("__demo__")

/**
 * Marks [GraphInfo.path]: the registry's identity path (the folder the user picked or cloned into), NOT the
 * folder that holds `pages/`, `journals/` and `assets/`. With a wiki subdir (`<root>/logseq/…`) the two
 * differ, and using it for file layout writes outside the folder git sync stages. Use
 * [GraphInfo.effectiveNotesPath] for anything that reads or writes notes; opt in only for identity
 * (registry keys, SAF/scheme checks, display, storage relocation) and say which in a comment.
 */
@RequiresOptIn(
    message = "GraphInfo.path is the repo root, not the notes folder. Use effectiveNotesPath for pages, " +
        "journals and assets; opt in only for graph identity, SAF checks, display or relocation.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class RegistryRootPath

/**
 * Information about a single graph (knowledge base).
 */
@Serializable
data class GraphInfo(
    val id: GraphId,           // sha256(canonicalPath).take(16)
    @property:RegistryRootPath val path: String,  // Canonical absolute path of the repo root (see RegistryRootPath)
    val displayName: String,  // User-facing name (defaults to directory name)
    val addedAt: Long,        // Epoch millis
    val isParanoidMode: Boolean = false,  // True when .stele-vault is present
    val detectedRepoRoot: String? = null,
    val detectedWikiSubdir: String? = null,
    val effectivePath: String? = null,
    val contentMismatchDetected: Boolean = false,
    val contentMismatchBannerDismissed: Boolean = false,
    val gitDetectionDismissed: Boolean = false,
    val browserOnlySyncBannerDismissed: Boolean = false,
    val isDemo: Boolean = false,
    /** Real host-folder name last linked via the File System Access API (web-local-folder-livesync
     * only) — e.g. "notes" for `~/Documents/notes`. Distinct from [path], which is this graph's own
     * internal identity path and does not change when the graph is relinked to a differently-named
     * host folder. Null on every platform without a host-directory concept, and until first linked. */
    val hostDirName: String? = null,
    /** Optional user-facing note to tell graphs apart in the switcher and header. */
    val description: String = "",
    /** Directory scanning candidates discovered during wiki-subdir-UX detection. */
    val directoryScanCandidates: List<DirectoryScanResult> = emptyList(),
) {
    // The blessed derivations of the identity path; everything else reads these.
    @OptIn(RegistryRootPath::class)
    val effectiveNotesPath: EffectiveNotesPath
        get() = EffectiveNotesPath(effectivePath ?: path)

    val wikiSubdirObj: WikiSubdir
        get() = WikiSubdir(detectedWikiSubdir ?: "")

    @OptIn(RegistryRootPath::class)
    val repoRootObj: RepoRoot
        get() = RepoRoot(detectedRepoRoot ?: path)

    @OptIn(RegistryRootPath::class)
    val location: GraphLocation
        get() = GraphLocation.from(path, detectedRepoRoot, detectedWikiSubdir, effectivePath)
}

/**
 * Registry of all known graphs and which one is active.
 */
@Serializable
data class GraphRegistry(
    val activeGraphId: GraphId? = null,
    val graphs: List<GraphInfo> = emptyList()
) {
    /**
     * Get the set of graph IDs for quick lookup
     */
    val graphIds: Set<GraphId>
        get() = graphs.map { it.id }.toSet()
}
