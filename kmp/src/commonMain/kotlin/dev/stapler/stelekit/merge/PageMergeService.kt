package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import kotlin.time.Clock

/**
 * Orchestrates a cross-graph page copy: `plan` (dry run) -> `stage` -> `apply` -> `retryFailed` / `cancel`;
 * undo is [MergeUndo] over the manifest `apply` writes.
 *
 * Typical use: `val plan = plan(request, source)`, show `plan.summary`, then `apply(plan)`
 * (stages on demand) and observe [progress]. The service keeps the plan's selection and source
 * in memory, so a plan only works with the instance that made it (else [ApplyFailure.UnknownPlan]).
 *
 * Target writes go through [router], which takes the target's `GraphWriteLock` per page batch;
 * this class takes no graph lock. One `apply`/`retryFailed` runs at a time ([ApplyFailure.Busy]).
 * Reads are bounded: source reads are <= [PageSource.MAX_PAGE_SIZE] pages, link lookups <= 500 names.
 *
 * @param closureLookup `PageRepository::getPagesByNames` of the source graph (link closure only)
 * @param assetCopier with [graphRoot], copies `../assets/` files and rewrites renamed links
 */
class PageMergeService(
    private val router: TargetWriterRouter,
    private val storage: MergeStorage,
    private val closureLookup: suspend (List<String>) -> Either<DomainError, List<Page>> = { emptyList<Page>().right() },
    private val assetCopier: AssetCopier? = null,
    private val graphRoot: (GraphId) -> String? = { null },
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newMergeId: () -> MergeId = { MergeId(UuidGenerator.generateV7()) },
) {
    /** Disk-backed staging and manifests under [appDataDir] (Desktop/Android). */
    constructor(
        router: TargetWriterRouter,
        fileSystem: FileSystem,
        appDataDir: String,
        closureLookup: suspend (List<String>) -> Either<DomainError, List<Page>> = { emptyList<Page>().right() },
        assetCopier: AssetCopier? = null,
        graphRoot: (GraphId) -> String? = { null },
        nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        newMergeId: () -> MergeId = { MergeId(UuidGenerator.generateV7()) },
    ) : this(
        router, okioMergeStorage(fileSystem, appDataDir), closureLookup, assetCopier, graphRoot, nowEpochMs, newMergeId,
    )

    private val logger = Logger("PageMergeService")
    private val manifests = storage.manifests

    // Owned scope per CLAUDE.md: never a caller-supplied or composition scope.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                if (e !is CancellationException) logger.error("merge scope: ${e::class.simpleName}: ${e.message}", e)
            },
    )

    private val _progress = MutableStateFlow(MergeProgress())
    val progress: StateFlow<MergeProgress> = _progress.asStateFlow()

    private val runLock = Mutex()
    private val cancelRequested = MutableStateFlow(false)
    private val stateLock = Mutex()
    private val contexts = LinkedHashMap<String, RunContext>()
    private var lastRun: RunState? = null

    private class RunContext(val request: PlanRequest, val source: PageSource, var sourceUnreadable: List<UnreadablePage>) {
        var staging: MergeStaging? = null
    }

    private class RunState(
        val plan: MergePlan,
        val ctx: RunContext,
        val staging: MergeStaging,
        val policy: MergePolicy,
    ) {
        var newPages = 0
        var combined = 0
        var unchanged = 0
        var conflicts = 0
        var assetsRenamed = 0
        var stoppedAfter: Int? = null
        val failed = ArrayList<PageFailure>()

        fun result() = MergeResult(
            plan.mergeId, plan.summary.total, newPages, combined, unchanged,
            failed.toList(), conflicts, assetsRenamed, stoppedAfter,
        )
    }

    /** Releases the owned scope. The service is unusable afterwards. */
    fun close() = scope.cancel()

    // ---- plan ----

    /**
     * Dry run: classifies every selected page against the target without writing. Streams <= 100 pages
     * at a time; the plan keeps only counters, <= [MergePlan.MAX_CONFLICT_DETAILS] conflict details and a fingerprint.
     */
    suspend fun plan(request: PlanRequest, source: PageSource): Either<DomainError, MergePlan> {
        val acc = Accumulator()
        val policy = policyOf(request)
        _progress.value = MergeProgress(MergePhase.Planning)
        source.takeUnreadable()
        val closure = forEachSourceChunk(request, source) { pages, _ ->
            classifyChunk(request, policy, pages, acc).also {
                _progress.update { p -> p.copy(done = acc.summary().total) }
            }
        }.fold({ e -> _progress.value = MergeProgress(); return e.left() }, { it })
        val sourceUnreadable = source.takeUnreadable()
        acc.unreadable += sourceUnreadable.size
        val plan = MergePlan(
            mergeId = newMergeId().value,
            sourceGraphId = request.sourceGraphId.value,
            targetGraphId = request.targetGraphId.value,
            direction = request.direction,
            summary = acc.summary(),
            conflictDetails = acc.conflictDetails.toList(),
            closure = ClosureSummary(closure.added.size, closure.notIncluded, closure.requiresConfirmation),
            planFingerprint = acc.fingerprint,
            createdAtEpochMs = nowEpochMs(),
        )
        stateLock.withLock {
            contexts[plan.mergeId] = RunContext(request, source, sourceUnreadable)
            while (contexts.size > MAX_PLANS_KEPT) contexts.remove(contexts.keys.first())
        }
        _progress.value = MergeProgress(MergePhase.Idle, total = plan.summary.total)
        logger.info("merge.plan id=${plan.mergeId} ${plan.summary} closureAdded=${closure.added.size}")
        return plan.right()
    }

    // ---- stage ----

    /** Spills every planned source page as a [StagedPage] JSON file. [apply] calls this when needed. */
    suspend fun stage(plan: MergePlan): Either<ApplyFailure, Unit> = stageCtx(plan).map { }

    private suspend fun stageCtx(plan: MergePlan): Either<ApplyFailure, MergeStaging> {
        val ctx = stateLock.withLock { contexts[plan.mergeId] } ?: return ApplyFailure.UnknownPlan.left()
        ctx.staging?.takeIf { it.pageCount() == plan.summary.total - ctx.sourceUnreadable.size }?.let { return it.right() }
        ctx.staging?.delete()
        val staging = storage.staging.create(
            MergeId(plan.mergeId),
            GraphId(plan.sourceGraphId), GraphId(plan.targetGraphId), nowEpochMs(),
        ).getOrNull() ?: return ApplyFailure.StagingFailed("Could not create the staging directory").left()
        _progress.value = MergeProgress(MergePhase.Staging, total = plan.summary.total)
        ctx.source.takeUnreadable()
        var index = 0
        val spilled = forEachSourceChunk(ctx.request, ctx.source) { pages, _ ->
            withContext(PlatformDispatcher.IO) {
                for (page in pages) {
                    val w = staging.writePage(index++, page)
                    if (w.isLeft()) return@withContext DomainError.FileSystemError.WriteFailed(
                        staging.label, (w as Either.Left).value.message,
                    ).left()
                }
                Unit.right()
            }.also { _progress.update { p -> p.copy(done = index) } }
        }
        spilled.onLeft { e ->
            staging.delete()
            return (if (e is DomainError.FileSystemError) ApplyFailure.StagingFailed(e.message) else ApplyFailure.Failed(e)).left()
        }
        ctx.sourceUnreadable = ctx.source.takeUnreadable()
        ctx.staging = staging
        return staging.right()
    }

    // ---- apply ----

    /**
     * Commits [plan]: re-checks the target against [MergePlan.planFingerprint] (stale -> `Left(PlanStale)`,
     * nothing written), then writes page by page, flushing the manifest per page. A page that fails
     * (including [DomainError.MergeError.WriteRefused] and retry-exhausted) is listed in
     * [MergeResult.failed] and kept in staging; copies never queue. [cancel] stops cleanly between pages.
     */
    suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult> {
        if (!runLock.tryLock()) return ApplyFailure.Busy.left()
        try {
            cancelRequested.value = false
            val job = scope.async { doApply(plan) }
            return try {
                job.await()
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            } catch (e: Throwable) {
                // async only delivers via await: without this a fault (OOM, a throwing PageSource) escapes
                // to the caller and leaves progress stuck in Staging/Applying.
                logger.error("merge.apply id=${plan.mergeId} crashed: ${e::class.simpleName}: ${e.message}", e)
                _progress.value = MergeProgress()
                ApplyFailure.Failed(DomainError.FileSystemError.WriteFailed("merge", e.message ?: e::class.simpleName.orEmpty())).left()
            }
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun doApply(plan: MergePlan): Either<ApplyFailure, MergeResult> {
        val staging = stageCtx(plan).fold({ return it.left() }, { it })
        val ctx = stateLock.withLock { contexts[plan.mergeId] } ?: return ApplyFailure.UnknownPlan.left()
        val policy = policyOf(ctx.request)

        val check = Accumulator()
        val checkError = forEachStagedChunk(staging) { _, pages -> classifyChunk(ctx.request, policy, pages, check) }
        checkError.onLeft { return ApplyFailure.Failed(it).left() }
        if (check.fingerprint != plan.planFingerprint) {
            _progress.value = MergeProgress()
            logger.info("merge.apply id=${plan.mergeId} stale ${check.summary()}")
            return ApplyFailure.PlanStale(check.summary()).left()
        }

        val manifest = manifests.begin(
            MergeId(plan.mergeId), plan.sourceGraphId, plan.targetGraphId, nowEpochMs(),
        ).getOrNull() ?: return ApplyFailure.StagingFailed("Could not create the undo manifest").left()

        val state = RunState(plan, ctx, staging, policy)
        val total = plan.summary.total
        _progress.value = MergeProgress(MergePhase.Applying, total = total)
        var processed = 0
        forEachStagedChunk(staging) { firstIndex, pages ->
            for ((offset, page) in pages.withIndex()) {
                if (cancelRequested.value) {
                    state.stoppedAfter = firstIndex + offset
                    return@forEachStagedChunk STOP.left()
                }
                applyOne(state, manifest, firstIndex + offset, page)
                processed = firstIndex + offset + 1
                _progress.value = MergeProgress(MergePhase.Applying, processed, total, state.failed.size)
            }
            Unit.right()
        }.onLeft { if (it !== STOP) return ApplyFailure.Failed(it).left() }
        // Unreadable source pages were never staged: failed with their reason (index -1 is never retryable from staging).
        ctx.sourceUnreadable.forEach { state.failed += PageFailure(-1, it.name, it.error) }
        manifest.complete()
        return finishRun(state).right()
    }

    private fun finishRun(state: RunState): MergeResult {
        val result = state.result()
        if (state.failed.isEmpty() && state.stoppedAfter == null) {
            state.staging.delete()
            lastRun = null
        } else {
            lastRun = state
        }
        _progress.value = MergeProgress(
            if (state.stoppedAfter != null) MergePhase.Stopped else MergePhase.Finished,
            result.total - result.notAttempted, result.total, result.failed.size,
        )
        logger.info(
            "merge.apply id=${result.mergeId} new=${result.newPages} combined=${result.combinedPages} " +
                "unchanged=${result.unchangedPages} failed=${result.failed.size} notAttempted=${result.notAttempted}",
        )
        return result
    }

    /** Requests a stop after the page being written. Committed pages stay; re-planning converges. */
    fun cancel() {
        if (runLock.isLocked) cancelRequested.value = true
    }

    /** Re-applies only the pages that failed in the last run, from staging. Returns the cumulative result. */
    suspend fun retryFailed(): Either<ApplyFailure, MergeResult> {
        if (!runLock.tryLock()) return ApplyFailure.Busy.left()
        try {
            val state = lastRun ?: return ApplyFailure.Busy.left()
            val manifest = manifests.writerFor(MergeId(state.plan.mergeId))
                ?: return ApplyFailure.StagingFailed("Undo manifest is missing").left()
            val pending = state.failed.toList()
            state.failed.clear()
            _progress.value = MergeProgress(MergePhase.Applying, 0, pending.size)
            for ((i, failure) in pending.withIndex()) {
                val page = readStaged(state.staging, failure.stagedIndex)
                if (page == null) {
                    state.failed += failure
                } else {
                    applyOne(state, manifest, failure.stagedIndex, page)
                }
                _progress.value = MergeProgress(MergePhase.Applying, i + 1, pending.size, state.failed.size)
            }
            manifest.complete()
            return finishRun(state).right()
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun readStaged(staging: MergeStaging, index: Int): MergePage? =
        withContext(PlatformDispatcher.IO) { staging.readPage(index) }

    private suspend fun applyOne(state: RunState, manifest: MergeManifestLog, index: Int, page: MergePage) {
        val applied = applyPage(state.ctx.request, state.policy, page)
        when (applied) {
            is Either.Left -> state.failed += PageFailure(index, page.name, applied.value)
            is Either.Right -> {
                val a = applied.value
                a.entry?.let { entry ->
                    manifest.appendPage(entry).onLeft {
                        state.failed += PageFailure(
                            index, page.name,
                            DomainError.FileSystemError.WriteFailed("manifest", "Undo record could not be written: ${it.message}"),
                        )
                        return
                    }
                }
                when (a.kind) {
                    PageKind.New -> state.newPages++
                    PageKind.Combined -> state.combined++
                    PageKind.Unchanged -> state.unchanged++
                }
                if (a.conflicts > 0) state.conflicts++
                state.assetsRenamed += a.assetsRenamed
            }
        }
    }

    private class PageApplied(val kind: PageKind, val conflicts: Int, val assetsRenamed: Int, val entry: ManifestPageEntry?)

    private suspend fun applyPage(request: PlanRequest, policy: MergePolicy, page: MergePage): Either<DomainError, PageApplied> {
        val key = PageKey(page.name, page.isJournal)
        return try {
            router.withWriter(request.targetGraphId) { w ->
                val existing = w.readExisting(key).fold({ return@withWriter it.left() }, { it })
                val assets = copyAssets(request, page, dryRun = false).fold({ return@withWriter it.left() }, { it })
                val incoming = assets?.page ?: page
                val renamed = assets?.assetsRenamed ?: 0
                when (val outcome = mergePage(existing, incoming, policy)) {
                    MergeOutcome.Unchanged -> PageApplied(PageKind.Unchanged, 0, renamed, null).right()
                    is MergeOutcome.New -> write(w, key, existing, outcome.page, PageKind.New, 0, renamed)
                    is MergeOutcome.Merged ->
                        write(w, key, existing, outcome.page, PageKind.Combined, outcome.conflicts.size, renamed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(page.name, e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    private suspend fun write(
        w: TargetWriter,
        key: PageKey,
        existing: MergePage?,
        merged: MergePage,
        kind: PageKind,
        conflicts: Int,
        renamed: Int,
    ): Either<DomainError, PageApplied> =
        w.write(key, merged).fold({ it.left() }) { outcome ->
            when (outcome) {
                WriteOutcome.Unchanged -> PageApplied(PageKind.Unchanged, 0, renamed, null).right()
                is WriteOutcome.Created -> PageApplied(
                    kind, conflicts, renamed,
                    ManifestPageEntry(key.name, listOf(CreatedFile(outcome.path, outcome.contentHash)), isJournal = key.isJournal),
                ).right()
                is WriteOutcome.Updated -> {
                    // Hash what is on disk now (as undo will see it), not the in-memory merge.
                    val onDisk = w.readExisting(key).fold({ return it.left() }, { it })
                    val added = newTopLevelUuids(existing, merged)
                    val byUuid = onDisk?.let { indexByUuid(it.blocks) }.orEmpty()
                    val hashes = added.mapNotNull { u -> byUuid[u]?.let { u to BlockContentHash.of(it) } }.toMap()
                    PageApplied(
                        kind, conflicts, renamed,
                        ManifestPageEntry(key.name, addedBlockUuids = hashes.keys.toList(), addedBlockHashes = hashes, isJournal = key.isJournal),
                    ).right()
                }
            }
        }

    // ---- shared classification (plan and the apply-time stale check) ----

    private enum class PageKind { New, Combined, Unchanged }

    private class Accumulator {
        var new = 0
        var combined = 0
        var unchanged = 0
        var conflicts = 0
        var unreadable = 0
        var assetsRenamed = 0
        var fingerprint = ""
        val conflictDetails = ArrayList<PlanConflict>()

        fun summary() = DryRunSummary(new, combined, unchanged, conflicts, unreadable, assetsRenamed)
    }

    private sealed interface Classified {
        val name: String

        data class Unreadable(override val name: String, val error: DomainError) : Classified

        class Ok(
            override val name: String,
            val kind: PageKind,
            val targetDigest: String,
            val conflicts: List<BlockConflict>,
            val assetsRenamed: Int,
        ) : Classified
    }

    private suspend fun classifyChunk(
        request: PlanRequest,
        policy: MergePolicy,
        pages: List<MergePage>,
        acc: Accumulator,
    ): Either<DomainError, Unit> {
        val prepared = ArrayList<Pair<MergePage, Int>>(pages.size)
        for (page in pages) {
            val assets = copyAssets(request, page, dryRun = true).fold({ return it.left() }, { it })
            prepared += (assets?.page ?: page) to (assets?.assetsRenamed ?: 0)
        }
        val results = router.withWriter(request.targetGraphId) { w ->
            prepared.map { (page, renamed) -> classifyOne(w, policy, page, renamed) }.right()
        }.fold({ return it.left() }, { it })
        for (r in results) {
            when (r) {
                is Classified.Unreadable -> {
                    acc.unreadable++
                    acc.fingerprint = chain(acc.fingerprint, r.name, "unreadable")
                }
                is Classified.Ok -> {
                    when (r.kind) {
                        PageKind.New -> acc.new++
                        PageKind.Combined -> acc.combined++
                        PageKind.Unchanged -> acc.unchanged++
                    }
                    if (r.conflicts.isNotEmpty()) acc.conflicts++
                    acc.assetsRenamed += r.assetsRenamed
                    for (c in r.conflicts) {
                        if (acc.conflictDetails.size < MergePlan.MAX_CONFLICT_DETAILS) {
                            acc.conflictDetails += PlanConflict(c.pageName, c.targetUuid, c.incomingUuid)
                        }
                    }
                    acc.fingerprint = chain(acc.fingerprint, r.name, r.targetDigest)
                }
            }
        }
        return Unit.right()
    }

    private suspend fun classifyOne(w: TargetWriter, policy: MergePolicy, page: MergePage, renamed: Int): Classified {
        val existing = w.readExisting(PageKey(page.name, page.isJournal)).fold({ return Classified.Unreadable(page.name, it) }, { it })
        val digest = existing?.let { BlockContentHash.of(MergeBlock(null, it.name, it.properties, it.blocks)) } ?: "absent"
        return when (val outcome = mergePage(existing, page, policy)) {
            is MergeOutcome.New -> Classified.Ok(page.name, PageKind.New, digest, emptyList(), renamed)
            MergeOutcome.Unchanged -> Classified.Ok(page.name, PageKind.Unchanged, digest, emptyList(), renamed)
            is MergeOutcome.Merged -> Classified.Ok(page.name, PageKind.Combined, digest, outcome.conflicts, renamed)
        }
    }

    /** Null when assets are not configured for this pair of graphs. */
    private suspend fun copyAssets(request: PlanRequest, page: MergePage, dryRun: Boolean): Either<DomainError, AssetCopyResult?> {
        val copier = assetCopier ?: return null.right()
        val src = graphRoot(request.sourceGraphId) ?: return null.right()
        val dst = graphRoot(request.targetGraphId) ?: return null.right()
        return withContext(PlatformDispatcher.IO) { copier.copy(page, src, dst, dryRun) }
    }

    // ---- source / staging iteration ----

    /**
     * Visits the selection (then its link closure) in <= 100-page chunks, in a deterministic order.
     * Returns the closure outcome. The visitor's second argument is true for closure pages.
     */
    private suspend fun forEachSourceChunk(
        request: PlanRequest,
        source: PageSource,
        visit: suspend (List<MergePage>, Boolean) -> Either<DomainError, Unit>,
    ): Either<DomainError, ClosureResult> {
        val sel = request.selection
        val trackLinks = request.closure is LinkClosurePolicy.Depth1
        val selectedNames = HashSet<String>()
        val candidates = LinkedHashSet<String>()

        suspend fun emit(uuids: List<dev.stapler.stelekit.model.PageUuid>, closure: Boolean): Either<DomainError, Unit> {
            val read = source.readPages(uuids).fold({ return it.left() }, { it })
            val pages = read.map { it.toMergePage() }
            if (trackLinks && !closure) {
                pages.forEach { selectedNames += it.name.lowercase() }
                candidates += LinkClosure.candidateNames(pages)
            }
            return if (pages.isEmpty()) Unit.right() else visit(pages, closure)
        }

        val include = sel.include
        if (include != null) {
            val ids = include.filter { it !in sel.exclude }.sortedBy { it.value }
            for (chunk in ids.chunked(PageSource.MAX_PAGE_SIZE)) emit(chunk, false).onLeft { return it.left() }
        } else {
            var offset = 0
            while (true) {
                val listed = source.listPages(sel.filter, sel.search, PageSource.MAX_PAGE_SIZE, offset)
                    .fold({ return it.left() }, { it })
                emit(listed.filter { it.uuid !in sel.exclude }.map { it.uuid }, false).onLeft { return it.left() }
                if (listed.size < PageSource.MAX_PAGE_SIZE) break
                offset += listed.size
            }
        }

        if (!trackLinks) return ClosureResult.EMPTY.right()
        val resolved = LinkedHashMap<String, Page>()
        for (chunk in candidates.filter { it.lowercase() !in selectedNames }.chunked(LinkClosure.LOOKUP_CHUNK)) {
            val found = closureLookup(chunk).fold({ return it.left() }, { it })
            for (p in found) {
                val key = p.name.lowercase()
                if (key !in selectedNames && key !in resolved) resolved[key] = p
            }
        }
        val closure = LinkClosure.limit(resolved.values, request.closure)
        for (chunk in closure.added.chunked(PageSource.MAX_PAGE_SIZE)) {
            emit(chunk.map { it.uuid }, true).onLeft { return it.left() }
        }
        return closure.right()
    }

    /** Visits staged pages in index order, <= 100 at a time, with the index of the first. */
    private suspend fun forEachStagedChunk(
        staging: MergeStaging,
        visit: suspend (Int, List<MergePage>) -> Either<DomainError, Unit>,
    ): Either<DomainError, Unit> {
        val it = staging.readAll().iterator()
        var index = 0
        while (true) {
            val chunk = withContext(PlatformDispatcher.IO) {
                val out = ArrayList<Either<StagedPageError, MergePage>>(PageSource.MAX_PAGE_SIZE)
                while (out.size < PageSource.MAX_PAGE_SIZE && it.hasNext()) out += it.next()
                out
            }
            if (chunk.isEmpty()) return Unit.right()
            val pages = chunk.map { r ->
                r.fold({ e -> return DomainError.FileSystemError.ReadFailed(staging.label, e.message).left() }, { p -> p })
            }
            visit(index, pages).onLeft { return it.left() }
            index += pages.size
        }
    }

    private companion object {
        const val MAX_PLANS_KEPT = 4

        // Sentinel Left used to unwind the staged-page loop on cancel; never escapes this class.
        val STOP: DomainError = DomainError.MergeError.Retryable("stopped")
    }
}

private fun chain(prev: String, name: String, digest: String): String =
    "$prev|$name|$digest".encodeUtf8().sha256().hex()

private fun policyOf(request: PlanRequest) = MergePolicy(request.sourceGraphId, request.sourceGraphName)

private fun newTopLevelUuids(existing: MergePage?, merged: MergePage): List<String> {
    val before = existing?.let { indexByUuid(it.blocks).keys }.orEmpty()
    val out = ArrayList<String>()
    fun walk(blocks: List<MergeBlock>) {
        for (b in blocks) {
            if (b.uuid != null && b.uuid !in before) out += b.uuid else walk(b.children)
        }
    }
    walk(merged.blocks)
    return out
}

private fun indexByUuid(blocks: List<MergeBlock>): Map<String, MergeBlock> {
    val out = HashMap<String, MergeBlock>()
    fun walk(bs: List<MergeBlock>) {
        for (b in bs) {
            b.uuid?.let { out[it] = b }
            walk(b.children)
        }
    }
    walk(blocks)
    return out
}
