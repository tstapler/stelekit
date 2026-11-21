// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.platform.CanvasFileDropHost
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PngEncoder
import dev.stapler.stelekit.platform.SketchPlatformInput
import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.constraints.SolverTarget
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.constraints.SolverTierFacts
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.library.InMemoryLibraryStorage
import dev.stapler.stelekit.sketch.v2.library.LibraryStorage
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSettings
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.guide.TourProgress

/**
 * What the platform root hands the v2 editor besides the project bridge (Task 6.2.0d): the file seam of Import and Export, the file
 * system the image assets live on, the graph's notes folder and where the element library is kept. Every field defaults to
 * "not available", so a root that wires nothing shows no Import or Export and an in-memory library.
 */
class HostPlatform(
    val files: SketchFilePort? = null,
    val fileSystem: FileSystem? = null,
    /** The graph's notes folder (the one that holds `pages/`, `journals/` and `assets/`); null with no graph open. */
    val notesDirectory: () -> String? = { null },
    /** The platform's palm, cancel, edge-exclusion and raw-pointer seams of the canvas. */
    val input: SketchPlatformInput = SketchPlatformInput.None,
    /** The target class the edge-pan spike results are recorded for. */
    val edgeTarget: EdgeTarget = EdgeTarget.TouchDesktop,
    /** The persisted edge-pan choice; null leaves the strip Off. */
    val edgePan: EdgePanSettings? = null,
    /** What the browser storage is now: the durability cue reads the temporary-graph and private-window flags. */
    val storageStatus: () -> V2StorageStatus = { V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false) },
    /** The web canvas element's own drop and paste handler; null where the canvas has none (every other target). */
    val canvasDrop: CanvasFileDropHost? = null,
    /** The Finger drawing setting and the per-device stylus flag; null leaves Finger drawing off and shows no hint. */
    val fingerDrawing: FingerDrawingSettings? = null,
    /** What the user has already been shown of the feature tours; null shows no tours. */
    val tourProgress: TourProgress? = null,
)

/**
 * The host's collaborators, built once per open sheet by the opener. The assets convention is the one the image tests state: one
 * flat folder `<notes>/assets/`, files named by the SHA-256 of their bytes ([ImageAssetStore]). The element library is one
 * `.excalidrawlib` file beside the images, per graph.
 */
class HostServices(
    val files: SketchFilePort? = null,
    val images: ImageAssetStore? = null,
    val storage: () -> V2Storage = { V2Storage.Available },
    val library: LibraryStorage = InMemoryLibraryStorage(),
    /** The solver tier of this target, from the recorded measurements (ADR-003 item 7, [SolverTierFacts]). */
    val tier: () -> SolverTier = { SolverTierFacts.Recorded.tierOf(SolverTarget.Jvm) },
    val png: PngEncoder = PngEncoder(),
    /** Adds a sheet to the project beside the open one (the over-cap "new sketch" path); null where the host cannot. */
    val createSheet: (suspend (SketchDocumentV2) -> Either<String, Unit>)? = null,
    val limits: SceneLimits = SceneLimits.Default,
    /** What the platform root supplied (input seams, edge-pan settings, storage status). */
    val platform: HostPlatform = HostPlatform(),
    /** The named dimensions and zones of a technical sheet; null for a sheet that has none. */
    val technical: TechnicalDataSource? = null,
) {
    /** The stored bytes of an image element, for SVG and Excalidraw export; a missing file is a placeholder in the output. */
    val imageSource: SvgImageSource = SvgImageSource { fileId ->
        images?.read(fileId)?.let { bytes -> ImageHeaderProbe.probe(bytes).getOrNull()?.let { SvgImage(it.format.mime, bytes) } }
    }

    suspend fun newSheet(document: SketchDocumentV2): Either<String, Unit> = createSheet?.invoke(document) ?: UNAVAILABLE.left()

    companion object {
        /** The folder of the stored images under a notes folder. */
        fun assetsDirectory(notes: String): String = "${notes.trimEnd('/')}/assets"

        /** The element library file of a notes folder. */
        fun libraryPath(notes: String): String = "${assetsDirectory(notes)}/$LIBRARY_FILE"

        private const val LIBRARY_FILE = "sketch-library.excalidrawlib"
        private const val UNAVAILABLE = "unavailable"

        fun of(
            platform: HostPlatform,
            storage: () -> V2Storage,
            limits: SceneLimits,
            createSheet: (suspend (SketchDocumentV2) -> Either<String, Unit>)? = null,
            tier: () -> SolverTier = { SolverTierFacts.Recorded.tierOf(SolverTarget.Jvm) },
        ): HostServices {
            val notes = platform.notesDirectory()
            val fs = platform.fileSystem
            val images = if (notes != null && fs != null) ImageAssetStore(fs, assetsDirectory(notes), limits) else null
            val library = if (notes != null && fs != null) FileLibraryStorage(fs, libraryPath(notes)) else InMemoryLibraryStorage()
            return HostServices(platform.files, images, storage, library, tier, createSheet = createSheet, limits = limits, platform = platform)
        }
    }
}

/** The library text in one file; a failed write is dropped (the in-memory library stays right for this session). */
class FileLibraryStorage(private val fs: FileSystem, private val path: String) : LibraryStorage {
    override fun read(): String? = if (fs.fileExists(path)) fs.readFile(path) else null

    override fun write(text: String) {
        val directory = path.substringBeforeLast('/')
        if (!fs.directoryExists(directory)) fs.createDirectory(directory)
        fs.writeFile(path, text)
    }
}
