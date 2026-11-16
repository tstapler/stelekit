// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.platform.V2StorageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** The per-graph new-editor setting (ADR-005 item 1a): classic only, new projects only, or every sketch (Upgrade). */
enum class V2Mode {
    Off,
    NewProjectsOnly,
    On,
    ;

    companion object {
        /** A missing, blank or unknown stored value is [Off]. */
        fun parse(raw: String): V2Mode = entries.firstOrNull { it.name == raw } ?: Off
    }
}

/** The two build-time release decisions. [CONVERSION_READY] is held honest by `ConversionReadyConsistencyTest`. */
object V2Build {
    /** True only while the bundle, technical-data, codec, fusion/preview and router test classes are registered. Gates BOTH v2 modes. */
    const val CONVERSION_READY: Boolean = true

    /** False in a release shipped after an A4 NO-GO. Gates only conversion, mode `On` and the Upgrade chip (Task 1.4.3e). */
    const val CONVERSION_OFFERED: Boolean = true
}

/** Whether, and why not, this platform can store new-editor sketches right now. */
sealed interface V2Storage {
    data object Available : V2Storage

    /** A browser is still probing its storage (at most 10 s): never shown as a failure. */
    data object Checking : V2Storage

    /** No sidecar store: a demo graph, or no graph is open. */
    data object NoStore : V2Storage

    /** The platform cannot write files atomically at all (iOS). */
    data object PlatformUnsupported : V2Storage

    /** A browser that cannot save them; [iosWebKit] only selects wording (iPhone and iPad browsers share one engine). */
    data class Browser(val reason: StorageReason, val iosWebKit: Boolean = false) : V2Storage
}

/** `V2StorageAvailable`: true only for [V2Storage.Available]. */
val V2Storage.isAvailable: Boolean get() = this == V2Storage.Available

/** The storage case of this store: a null store is [V2Storage.NoStore]; otherwise the platform status, then the atomic-write check. */
fun ProjectSidecarStore?.v2Storage(iosWebKit: Boolean = false): V2Storage {
    if (this == null) return V2Storage.NoStore
    return when (val status = storageStatus()) {
        V2StorageStatus.Checking -> V2Storage.Checking
        is V2StorageStatus.Unavailable -> V2Storage.Browser(status.reason, iosWebKit)
        is V2StorageStatus.Ready -> if (isAvailable()) V2Storage.Available else V2Storage.PlatformUnsupported
    }
}

/** `V2StorageAvailable` of a possibly-null store (a null store is false). */
fun ProjectSidecarStore?.v2StorageAvailable(): Boolean = v2Storage().isAvailable

/** Why a mode option is disabled; [Storage] comes before [BuildCapped] (one precedence for every new-editor control, ux.md 14.1). */
sealed interface ModeDisabled {
    data object Checking : ModeDisabled
    data class Storage(val storage: V2Storage) : ModeDisabled
    data object BuildCapped : ModeDisabled
}

/** The three caps over a stored mode: `ConversionReady`, `ConversionOffered` and `V2StorageAvailable`. */
data class V2Capabilities(
    val conversionReady: Boolean,
    val conversionOffered: Boolean,
    val storage: V2Storage,
) {
    /** The mode the router and the creation rules actually use. */
    fun effective(stored: V2Mode): V2Mode = when {
        !storage.isAvailable || !conversionReady -> V2Mode.Off
        stored == V2Mode.On && !conversionOffered -> V2Mode.NewProjectsOnly
        else -> stored
    }

    /** Null when [option] can be chosen. Off is never disabled. */
    fun disabledReason(option: V2Mode): ModeDisabled? = when {
        option == V2Mode.Off -> null
        storage == V2Storage.Checking -> ModeDisabled.Checking
        !storage.isAvailable -> ModeDisabled.Storage(storage)
        !conversionReady -> ModeDisabled.BuildCapped
        option == V2Mode.On && !conversionOffered -> ModeDisabled.BuildCapped
        else -> null
    }

    companion object {
        fun of(storage: V2Storage) = V2Capabilities(V2Build.CONVERSION_READY, V2Build.CONVERSION_OFFERED, storage)
    }
}

/** [V2ModeFlag.set] was called while no graph is ready. */
data object NoGraph

/**
 * The mode of the READY graph. Not a copy of `HomeSketchFlag`: it is keyed by graph id, so a graph switch re-reads
 * the new graph's stored string and never shows the other graph's mode. [readyGraphId] comes from
 * `GraphManager.readyGraph.map { it?.id?.value }` and [currentReadyGraphId] from `GraphManager.readyGraphId`.
 * Owns no scope; the collector of [mode] does.
 */
class V2ModeFlag(
    private val settings: ProjectBridgeSettings,
    readyGraphId: Flow<String?>,
    private val currentReadyGraphId: () -> String?,
) {
    private val revision = MutableStateFlow(0)

    val mode: Flow<V2Mode> = combine(readyGraphId, revision) { id, _ -> id?.let(settings::v2Mode) ?: V2Mode.Off }
        .distinctUntilChanged()

    /** The stored mode of the graph that is ready now, read per call. */
    fun current(): V2Mode = currentReadyGraphId()?.let(settings::v2Mode) ?: V2Mode.Off

    /** Writes under the graph that is ready at the call; with none ready it writes nothing. */
    fun set(mode: V2Mode): Either<NoGraph, Unit> {
        val id = currentReadyGraphId() ?: return NoGraph.left()
        settings.setV2Mode(id, mode)
        revision.update { it + 1 }
        return Unit.right()
    }
}
