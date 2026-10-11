package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.platform.FileSystem

/**
 * Spike 0.1.5 (read-only access to an inactive graph on iOS: security-scoped bookmark) has NOT
 * been run: no device. Until it records "yes" and this flips, every iOS source is refused as
 * [ReadCapabilityReason.PlatformUnsupported].
 */
const val IOS_OFF_GRAPH_READ_VERIFIED = false

/** Spike 0.1.5 for Web (persisted `FileSystemDirectoryHandle` / OPFS): not run; same degradation. */
const val WEB_OFF_GRAPH_READ_VERIFIED = false

enum class SourcePlatform { Desktop, Android, Ios, Web }

/** Why an inactive graph cannot be used as a pull source. Rendered by the source chooser as disabled-with-reason. */
sealed interface ReadCapabilityReason {
    val userText: String
    val action: CapabilityAction?

    data object Encrypted : ReadCapabilityReason {
        override val userText = "This graph is encrypted, so it can only be read while it is open."
        override val action = CapabilityAction.OpenGraph
    }

    data class NoGrant(val canRegrant: Boolean = true) : ReadCapabilityReason {
        override val userText = "SteleKit no longer has permission to read this graph's folder."
        override val action = if (canRegrant) CapabilityAction.RegrantAccess else CapabilityAction.OpenGraph
    }

    data object FolderMissing : ReadCapabilityReason {
        override val userText = "This graph's folder could not be found."
        override val action: CapabilityAction? = null
    }

    data object PlatformUnsupported : ReadCapabilityReason {
        override val userText = "This device cannot read a graph that is not open."
        override val action: CapabilityAction? = null
    }

    data object UnreadableIo : ReadCapabilityReason {
        override val userText = "This graph's folder could not be read."
        override val action: CapabilityAction? = null
    }
}

/**
 * Sibling of [TargetWriterCapabilities] for pull sources. Defaults are conservative: anything not
 * positively known to work is refused. The active graph is not special-cased here (the chooser
 * lists it as "current graph" itself).
 *
 * @param hasGrant true only for a folder grant confirmed still held
 * @param folderExists whether the graph folder is still present
 * @param isReadable whether the platform can currently read under the folder
 */
class SourceReadCapabilities(
    private val platform: SourcePlatform,
    private val iosVerified: Boolean = IOS_OFF_GRAPH_READ_VERIFIED,
    private val webVerified: Boolean = WEB_OFF_GRAPH_READ_VERIFIED,
    private val hasGrant: (OffGraphTarget) -> Boolean = { true },
    private val canRegrant: Boolean = false,
    private val folderExists: (String) -> Boolean = { true },
    private val isReadable: (String) -> Boolean = { true },
) {
    fun canReadOffGraph(graph: OffGraphTarget): Either<ReadCapabilityReason, Unit> {
        if (graph.encrypted) return ReadCapabilityReason.Encrypted.left()
        val verified = when (platform) {
            SourcePlatform.Ios -> iosVerified
            SourcePlatform.Web -> webVerified
            SourcePlatform.Desktop, SourcePlatform.Android -> true
        }
        if (!verified) return ReadCapabilityReason.PlatformUnsupported.left()
        if (!hasGrant(graph)) return ReadCapabilityReason.NoGrant(canRegrant).left()
        if (!folderExists(graph.path)) return ReadCapabilityReason.FolderMissing.left()
        if (!isReadable(graph.path)) return ReadCapabilityReason.UnreadableIo.left()
        return Unit.right()
    }

    companion object {
        fun forFileSystem(platform: SourcePlatform, fs: FileSystem, canRegrant: Boolean = false) = SourceReadCapabilities(
            platform = platform,
            hasGrant = { fs.hasStoragePermission() },
            canRegrant = canRegrant,
            folderExists = fs::directoryExists,
        )
    }
}
