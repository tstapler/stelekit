// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.platform.isReady

/** The Option W verdict as the classic file side sees it. */
enum class WebFileSideVerdict {
    Available,
    Unavailable,
    Checking,
    ;

    companion object {
        fun of(status: V2StorageStatus): WebFileSideVerdict = when {
            status.isReady -> Available
            status == V2StorageStatus.Checking -> Checking
            else -> Unavailable
        }
    }
}

/** The per-browser `sketch.web.classicFileSide` setting; the default is [Off] (D-147). */
enum class ClassicFileSideSetting {
    Off,
    On,
    ;

    companion object {
        fun parse(raw: String): ClassicFileSideSetting = entries.firstOrNull { it.name == raw } ?: Off
    }
}

/** The kind of the sheet whose file side is being decided. */
enum class SheetFileKind { Classic, V2 }

/**
 * The pure per-sheet gate of the classic file side on the web (the `classic-web-file-side` block of requirements.md,
 * D-147, D-153, D-154): a sheet's file side runs only when the verdict is Available and (the setting is On or the sheet is v2).
 */
object ClassicWebFileSidePolicy {
    fun fileSideRuns(verdict: WebFileSideVerdict, setting: ClassicFileSideSetting, kind: SheetFileKind): Boolean =
        verdict == WebFileSideVerdict.Available && (setting == ClassicFileSideSetting.On || kind == SheetFileKind.V2)

    /** The kind a sidecar-relative file path belongs to; null for a path that is no sheet (model, sources, exports). */
    fun kindOfSheetPath(relPath: String): SheetFileKind? {
        val name = relPath.substringAfterLast('/')
        val inSheets = relPath.startsWith("${SheetPaths.SHEETS_DIR}/")
        return when {
            inSheets && name.endsWith(V2_SUFFIX) -> SheetFileKind.V2
            inSheets && name.endsWith(".json") -> SheetFileKind.Classic
            else -> null
        }
    }

    internal const val V2_SUFFIX = ".v2.json"
}

/**
 * Installs [ClassicWebFileSidePolicy] at the sidecar boundary of the web build: with the setting Off, a project that
 * holds no v2 sheet is device-local (reads answer Unavailable, writes write nothing), so every classic sheet stays
 * database-only. A project that holds a v2 sheet passes through unchanged; splitting the classic sheets out of a mixed
 * bundle needs the reconciler's cooperation and is the discovery work of Task 1.4.7n.
 *
 * [gateActive] is true only where the file system opts in (`FileSystem.classicFileSideNeedsOptIn`).
 */
class ClassicGatedSidecarStore(
    private val delegate: ProjectSidecarStore,
    private val gateActive: Boolean,
    private val setting: () -> ClassicFileSideSetting,
) : ProjectSidecarStore by delegate {
    private fun closed(): Boolean = gateActive &&
        setting() == ClassicFileSideSetting.Off &&
        WebFileSideVerdict.of(delegate.storageStatus()) == WebFileSideVerdict.Available

    private fun holdsV2Sheet(paths: Collection<String>): Boolean =
        paths.any { ClassicWebFileSidePolicy.kindOfSheetPath(it) == SheetFileKind.V2 }

    override suspend fun read(slug: String): SidecarRead {
        val read = delegate.read(slug)
        return if (closed() && read is SidecarRead.Present && !holdsV2Sheet(read.files.keys)) SidecarRead.Unavailable else read
    }

    override suspend fun write(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome =
        if (closed() && !holdsV2Sheet(files.keys)) SidecarWriteOutcome.Unavailable else delegate.write(slug, files, opaque)

    override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome =
        if (closed() && !holdsV2Sheet(files.keys)) SidecarWriteOutcome.Unavailable else delegate.writeNow(slug, files, opaque)
}
