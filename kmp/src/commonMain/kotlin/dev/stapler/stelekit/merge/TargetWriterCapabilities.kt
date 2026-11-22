package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.EffectiveNotesPath

/**
 * Spike 0.1.2 (SAF atomic replace) has NOT been run: no device. Until it records "yes" and this
 * flips, every SAF-backed inactive target degrades to [WriteCapabilityReason.SafInboxOnly].
 */
const val SAF_ATOMIC_REPLACE_VERIFIED = false

/** What the policy needs to know about a target; the active graph is always writable (via ActiveTargetWriter). */
data class OffGraphTarget(
    val graphId: GraphId,
    val path: EffectiveNotesPath,
    val isActive: Boolean,
    /** True when the graph has a `CryptoLayer` (`.md.stek` files). */
    val encrypted: Boolean = false,
    val storage: StorageLocation? = null,
)

/**
 * One place that answers "can this target be written off-graph", shared by the router and the picker.
 * Defaults are conservative: anything not positively known to work is refused.
 *
 * @param platformSupportsOffGraphWrite false on iOS/Web, where the path cannot be addressed while inactive
 * @param hasVerifiedSafGrant true only for a grant confirmed still held (not merely persisted)
 */
class TargetWriterCapabilities(
    private val platformSupportsOffGraphWrite: Boolean = false,
    private val hasVerifiedSafGrant: (StorageLocation.SafFolder) -> Boolean = { false },
    private val canRegrantSaf: Boolean = false,
    private val safAtomicReplaceVerified: Boolean = SAF_ATOMIC_REPLACE_VERIFIED,
) {
    fun canWriteOffGraph(target: OffGraphTarget): Either<WriteCapabilityReason, Unit> {
        if (target.isActive) return Unit.right()
        if (target.encrypted) return WriteCapabilityReason.Encrypted.left()
        val storage = target.storage
        if (!platformSupportsOffGraphWrite || storage is StorageLocation.HostFolder) {
            return WriteCapabilityReason.PlatformUnsupported.left()
        }
        val saf = storage is StorageLocation.SafFolder || target.path.value.startsWith("saf://") || target.path.value.startsWith("content://")
        if (!saf) return Unit.right()
        val granted = storage is StorageLocation.SafFolder && hasVerifiedSafGrant(storage)
        return when {
            !granted -> WriteCapabilityReason.NoGrant(canRegrantSaf).left()
            !safAtomicReplaceVerified -> WriteCapabilityReason.SafInboxOnly.left()
            else -> Unit.right()
        }
    }
}
