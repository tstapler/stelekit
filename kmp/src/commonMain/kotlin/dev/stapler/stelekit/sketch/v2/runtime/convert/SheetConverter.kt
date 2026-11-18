// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.convert

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.CorruptReason
import dev.stapler.stelekit.projectbridge.LocalBundleLoader
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetCodecV1
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarBehindGuard
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.v2.bridge.LiftError
import dev.stapler.stelekit.sketch.v2.bridge.V1ToV2
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.toScenePrimitive
import dev.stapler.stelekit.ui.sketch.toSheetProps
import dev.stapler.stelekit.util.ContentHasher

/** Why a conversion did not happen. Nothing is lost in any case: the v1 sheet stays the referenced one. */
enum class ConversionFailure(val token: String) {
    /** Refused only when [ConversionPolicy.TECHNICAL_DATA_EDITABLE] is overridden off: the project has zones or dimensions (Story 1.1.4). */
    HasTechnicalData("technical-data"),
    NotFound("not-found"),

    /** This platform cannot store sidecar files, so a v2 sheet cannot be saved here (Story 1.4.3, D-104). */
    StorageUnavailable("storage-unavailable"),

    /** The sidecar on disk is unreadable; it is kept untouched and never repaired by a conversion. */
    CorruptSidecar("corrupt-sidecar"),

    /** The sidecar was written by a newer build: this build must not write anything under it (UX-68). */
    NewerSchema("newer-schema"),

    /** The v1 sheet cannot be lifted into a v2 scene within the scene limits, or holds a primitive v2 cannot carry. */
    CannotLift("cannot-lift"),
    Encode("encode"),
    Write("write"),

    /** A written file did not read back as the bytes that were planned. */
    Verify("verify"),

    /** `ConversionReady` is false in this build: the conversion was not attempted and nothing was written. */
    NotReady("not-ready"),

    /** A git sync was in flight, or a pull brought changes not yet reconciled here; nothing was written, retry later. */
    Deferred("deferred"),
}

sealed interface ConversionOutcome {
    data class Converted(val sheet: SheetUuid, val elements: Int) : ConversionOutcome

    /** The sheet is already schema 2; nothing was read beyond the model and nothing was written. */
    data object AlreadyConverted : ConversionOutcome

    /** `ConversionReady` is false in this build: nothing was written and no backup exists (review R6). */
    data object NotReady : ConversionOutcome

    /** A git sync is in flight. The partial state is crash-safe; the next trigger retries. Nothing is parked. */
    data object Deferred : ConversionOutcome

    /** No acknowledgement exists for this sketch project yet (D-16); the router shows the first-write dialog. */
    data class RequiresConfirmation(val slug: String) : ConversionOutcome

    data class Failed(val reason: ConversionFailure, val detail: String = "") : ConversionOutcome
}

/** The failure the user is told about for this outcome; null when the outcome is not a refusal or failure. */
fun ConversionOutcome.failureReason(): ConversionFailure? = when (this) {
    is ConversionOutcome.Failed -> reason
    ConversionOutcome.NotReady -> ConversionFailure.NotReady
    ConversionOutcome.Deferred -> ConversionFailure.Deferred
    is ConversionOutcome.Converted, ConversionOutcome.AlreadyConverted, is ConversionOutcome.RequiresConfirmation -> null
}

/** The steps of the protocol, each a crash boundary. */
enum class ConversionStep { BackupWritten, V2Staged, V2Verified, ModelFlipped, V1Removed }

/** Test seam: called after each step completes; a test throws from it to simulate the process dying there. */
fun interface ConversionCrashPoint {
    fun after(step: ConversionStep)

    companion object {
        val None = ConversionCrashPoint { }
    }
}

/** Whether this device (not synced) has acknowledged the whole-project consent for the sketch project [slug] (D-16). */
fun interface ProjectAcknowledgements {
    suspend fun isAcknowledged(slug: String): Boolean

    companion object {
        val Always = ProjectAcknowledgements { true }
    }
}

/**
 * Converts one v1 sheet to a v2 sheet on the explicit Upgrade action (Story 1.4.2, ADR-001). The v1 file stays the
 * referenced, valid sheet until ONE model write flips the reference, so a crash at any boundary leaves a readable project:
 *
 * 1. S1 `backup/<uuid>.v1.json` is written (never overwritten) and read back.
 * 2. S2 `sheets/<uuid>.v2.json` is staged beside the unchanged bundle (temp + rename).
 * 3. S3 the staged file is read back, hashed and decoded.
 * 4. S4 one model write names the v2 file, its schema and its sha (the v1 file is protected from the sweep for this write).
 * 5. S5 the now-unreferenced v1 file is removed by the next write; the database row follows.
 *
 * The whole run holds the project's [SidecarSlugLock] and writes through `writeNow`, which returns Deferred while a
 * git sync is busy and keeps nothing parked, so a replayed write can never undo the flip.
 */
class SheetConverter(
    private val repos: SketchRepositories,
    private val store: ProjectSidecarStore,
    private val slugLock: SidecarSlugLock,
    private val acknowledgements: ProjectAcknowledgements,
    private val conversionReady: () -> Boolean,
    private val writeActor: DatabaseWriteActor? = null,
    private val log: SketchLog = SketchLog(),
    private val limits: SceneLimits = SceneLimits.Default,
    private val technicalDataEditable: Boolean = ConversionPolicy.TECHNICAL_DATA_EDITABLE,
    private val crashPoint: ConversionCrashPoint = ConversionCrashPoint.None,
) {
    /** Converts [sheet] of [projectUuid]. Idempotent: a second run on a converted sheet changes nothing. */
    suspend fun convert(projectUuid: ProjectUuid, sheet: SheetUuid): ConversionOutcome {
        val loaded = repos.projects.loadProject(projectUuid).getOrNull() ?: return failed(ConversionFailure.NotFound, "project")
        val slug = loaded.project.slug
        return slugLock.withSlug(slug) { convertLocked(loaded.project.uuid, slug, sheet).also { log.migrateOutcome(token(it)) } }
    }

    private suspend fun convertLocked(projectUuid: ProjectUuid, slug: String, sheet: SheetUuid): ConversionOutcome {
        val read = store.read(slug)
        val diskFiles = when (read) {
            SidecarRead.Unavailable -> return failed(ConversionFailure.StorageUnavailable)
            is SidecarRead.Corrupt ->
                return failed(if (read.reason == CorruptReason.NewerSchema) ConversionFailure.NewerSchema else ConversionFailure.CorruptSidecar)
            SidecarRead.Absent -> null
            is SidecarRead.Present -> read.files
        }
        val project = repos.projects.loadProject(projectUuid).getOrNull()?.project ?: return failed(ConversionFailure.NotFound, "project")
        val local = LocalBundleLoader.load(repos, project, diskFiles.orEmpty()).getOrNull() ?: return failed(ConversionFailure.Write, "local bundle")
        val row = local.sheets.firstOrNull { it.uuid == sheet } ?: return failed(ConversionFailure.NotFound, "sheet")
        if (row.schemaVersion == SheetPaths.SCHEMA_V2) return ConversionOutcome.AlreadyConverted
        if (ConversionPolicy.refusal(local, row, technicalDataEditable) != null) return failed(ConversionFailure.HasTechnicalData)
        if (!conversionReady()) return ConversionOutcome.NotReady
        if (!acknowledgements.isAcknowledged(slug)) return ConversionOutcome.RequiresConfirmation(slug)

        val plan = plan(local, row, diskFiles).fold({ return it }, { it })
        if (diskFiles != null && flippedOnDisk(diskFiles, plan)) return resumeFlipped(slug, plan, diskFiles)
        // The flip writes the local model over the disk's: a pulled, unreconciled sidecar would be reverted.
        if (diskFiles != null && SidecarBehindGuard.isBehind(project, diskFiles)) return ConversionOutcome.Deferred
        return execute(slug, plan)
    }

    /** A crash after the model write left the disk already converted while the database row still says v1. */
    private fun flippedOnDisk(disk: Map<String, ByteArray>, p: Plan): Boolean {
        val named = disk[SidecarCodec.MODEL_PATH]?.let { SidecarCodec.v2SheetShas(it) }?.get(p.v2Path)
        return named == p.v2Sha && disk[p.v2Path]?.let { ContentHasher.sha256(it) } == p.v2Sha
    }

    /**
     * Finishes S5 and the database row from the disk's own files, so nothing another device pushed is overwritten. The
     * project's reconcile base only moves when the local bundle now equals the disk bundle; otherwise the next reconcile decides.
     */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun resumeFlipped(slug: String, p: Plan, disk: Map<String, ByteArray>): ConversionOutcome {
        val cleanup = store.writeNow(slug, disk)
        if (cleanup == SidecarWriteOutcome.Unavailable) return failed(ConversionFailure.StorageUnavailable)
        crashPoint.after(ConversionStep.V1Removed)
        val token = repos.projects.loadProject(p.bundle.project.uuid).getOrNull()?.token ?: return failed(ConversionFailure.Write, "project token")
        write { repos.sheets.saveSheet(p.converted, token) }.onLeft { return failed(ConversionFailure.Write, "sheet row") }
        val fresh = repos.projects.loadProject(p.bundle.project.uuid).getOrNull()?.project ?: p.bundle.project
        val now = LocalBundleLoader.load(repos, fresh, disk).getOrNull()
        val diskHash = SidecarCodec.bundleHash(disk)
        if (now != null && SidecarCodec.bundleHash(SidecarCodec.encode(now)) == diskHash) {
            write { repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = diskHash, baseRevision = fresh.modelRevision)) }
                .onLeft { return failed(ConversionFailure.Write, "project base") }
        }
        return ConversionOutcome.Converted(p.row.uuid, p.elements)
    }

    /** Everything the writes need, computed before the first write so a refusal leaves no backup behind. */
    private class Plan(
        val bundle: ProjectBundle,
        val row: SketchSheet,
        val converted: SketchSheet,
        val v1Path: String,
        val v1Bytes: ByteArray,
        val v2Path: String,
        val v2Bytes: ByteArray,
        val v2Sha: String,
        val stageFiles: Map<String, ByteArray>,
        val flipped: ProjectBundle,
        val elements: Int,
    )

    private fun plan(local: ProjectBundle, row: SketchSheet, diskFiles: Map<String, ByteArray>?): Either<ConversionOutcome, Plan> {
        val sheetPrimitives = local.primitives.filter { it.sheetUuid == row.uuid }
        var scene = SketchScene.EMPTY
        for (stored in sheetPrimitives) {
            val primitive = stored.toScenePrimitive() ?: return failure(ConversionFailure.CannotLift, "primitive kind ${stored.kind}")
            scene = scene.add(primitive).getOrNull() ?: return failure(ConversionFailure.CannotLift, "scene rejected ${stored.uuid}")
        }
        val lifted = V1ToV2.lift(scene, row.toSheetProps(), limits).fold({ return failure(ConversionFailure.CannotLift, liftDetail(it)) }, { it })
        val doc = SketchDocumentV2(scene = lifted, sheetUuid = row.uuid.value)
        val v2Bytes = SheetCodecV2.encode(doc, limits).fold({ return failure(ConversionFailure.Encode, it::class.simpleName.orEmpty()) }, { it })
        // The round trip proves the staged file is one this build can read back (S3 repeats it on the bytes from disk).
        SheetCodecV2.decode(v2Bytes, limits).fold({ return failure(ConversionFailure.Encode, "re-decode") }, { })

        val v1Path = SheetPaths.pathFor(row.uuid, SheetPaths.SCHEMA_V1)
        val v2Path = SheetPaths.pathFor(row.uuid, SheetPaths.SCHEMA_V2)
        val v1Bytes = diskFiles?.get(v1Path) ?: SheetCodecV1.encode(row.uuid, sheetPrimitives)
        val converted = row.copy(schemaVersion = SheetPaths.SCHEMA_V2, layers = V2ToV1.projectScene(lifted).layers.map { it.encode() })
        val flipped = local.copy(
            sheets = local.sheets.map { if (it.uuid == row.uuid) converted else it },
            primitives = local.primitives.filter { it.sheetUuid != row.uuid },
            sheetPayloads = local.sheetPayloads + (row.uuid to SheetPayload.V2Opaque.of(v2Bytes)),
        )
        // Staging rewrites nothing the disk already holds: the unchanged bundle plus the new file.
        val base = diskFiles ?: SidecarCodec.encode(local)
        return Plan(local, row, converted, v1Path, v1Bytes, v2Path, v2Bytes, ContentHasher.sha256(v2Bytes), base + (v2Path to v2Bytes), flipped, lifted.elementsInOrder.size).right()
    }

    private suspend fun execute(slug: String, p: Plan): ConversionOutcome {
        store.writeBackup(slug, p.row.uuid.value, p.v1Bytes).onLeft { return failed(ConversionFailure.Write, "backup") }
        crashPoint.after(ConversionStep.BackupWritten)

        when (val staged = store.writeNow(slug, p.stageFiles)) {
            SidecarWriteOutcome.Written -> Unit
            else -> return stopped(staged, "stage")
        }
        crashPoint.after(ConversionStep.V2Staged)

        val back = store.readV2SheetFile(slug, p.row.uuid.value)
        if (back == null || ContentHasher.sha256(back) != p.v2Sha || SheetCodecV2.decode(back, limits).isLeft()) {
            return failed(ConversionFailure.Verify, "staged sheet")
        }
        crashPoint.after(ConversionStep.V2Verified)

        val modelFiles = SidecarCodec.encode(p.flipped)
        val otherV2 = p.flipped.sheetPayloads.filterKeys { it != p.row.uuid }
            .mapNotNull { (uuid, payload) -> (payload as? SheetPayload.V2Opaque)?.let { SheetPaths.pathFor(uuid, SheetPaths.SCHEMA_V2) to it.sha256 } }.toMap()
        // The v1 file is opaque for this one write so the sweep leaves it: its removal is the separate step S5.
        val protect = otherV2 + (p.v1Path to ContentHasher.sha256(p.v1Bytes)) + (p.v2Path to p.v2Sha)
        when (val flip = store.writeNow(slug, modelFiles - protect.keys, protect)) {
            SidecarWriteOutcome.Written -> Unit
            else -> return stopped(flip, "flip")
        }
        crashPoint.after(ConversionStep.ModelFlipped)

        // A refused or deferred cleanup is harmless: the next write of this project removes the unreferenced v1 file.
        store.writeNow(slug, modelFiles - (otherV2.keys + p.v2Path), otherV2 + (p.v2Path to p.v2Sha))
        crashPoint.after(ConversionStep.V1Removed)

        return finishDatabase(p, SidecarCodec.bundleHash(modelFiles))
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun finishDatabase(p: Plan, hash: String): ConversionOutcome {
        val token = repos.projects.loadProject(p.bundle.project.uuid).getOrNull()?.token ?: return failed(ConversionFailure.Write, "project token")
        write { repos.sheets.saveSheet(p.converted, token) }.onLeft { return failed(ConversionFailure.Write, "sheet row") }
        val fresh = repos.projects.loadProject(p.bundle.project.uuid).getOrNull()?.project ?: p.bundle.project
        write { repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = hash, baseRevision = fresh.modelRevision)) }
            .onLeft { return failed(ConversionFailure.Write, "project base") }
        return ConversionOutcome.Converted(p.row.uuid, p.elements)
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        writeActor?.execute { op() } ?: op()

    private fun stopped(outcome: SidecarWriteOutcome, step: String): ConversionOutcome = when (outcome) {
        SidecarWriteOutcome.Deferred -> ConversionOutcome.Deferred
        SidecarWriteOutcome.Unavailable -> failed(ConversionFailure.StorageUnavailable)
        SidecarWriteOutcome.StaleOpaque -> failed(ConversionFailure.Verify, "$step: a sheet file changed")
        is SidecarWriteOutcome.Failed -> failed(ConversionFailure.Write, step)
        SidecarWriteOutcome.Written -> error("not a stop")
    }

    private fun failed(reason: ConversionFailure, detail: String = ""): ConversionOutcome.Failed = ConversionOutcome.Failed(reason, detail)

    private fun failure(reason: ConversionFailure, detail: String): Either<ConversionOutcome, Nothing> = failed(reason, detail).left()

    private fun liftDetail(e: LiftError): String = e::class.simpleName.orEmpty()

    private fun token(o: ConversionOutcome): String = when (o) {
        is ConversionOutcome.Converted -> "converted"
        ConversionOutcome.AlreadyConverted -> "already"
        ConversionOutcome.NotReady -> "not-ready"
        ConversionOutcome.Deferred -> "deferred"
        is ConversionOutcome.RequiresConfirmation -> "needs-confirmation"
        is ConversionOutcome.Failed -> o.reason.token
    }
}
