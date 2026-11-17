// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.merge.SketchBundleCopy
import dev.stapler.stelekit.merge.SketchCopyError
import dev.stapler.stelekit.merge.SketchCopyResult
import dev.stapler.stelekit.merge.SketchOpenTarget
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A graph a sketch can be copied to. */
data class SketchCopyTarget(val id: GraphId, val name: String)

/**
 * What the Sketch screen needs to copy a whole sketch project to another graph (Story 6.1.2): the other graphs it may go to, the
 * copy itself through the app's one `TargetWriterRouter`, and where "Open" lands. Builds [SketchBundleCopy] per call from the
 * graph that is open now, so a graph switch never leaves it holding a stale store.
 */
class SketchCopyHost(
    private val router: () -> TargetWriterRouter?,
    private val store: () -> ProjectSidecarStore?,
    private val fileSystem: FileSystem,
    private val graphRoot: () -> String?,
    private val registry: () -> GraphRegistry,
    private val switchTo: (GraphId) -> Unit,
) {
    /** The registered graphs other than the open one and the demo graph, in registry order. */
    fun targets(): List<SketchCopyTarget> {
        val current = registry()
        return current.graphs.filter { it.id != current.activeGraphId && !it.isDemo }.map { SketchCopyTarget(it.id, it.displayName) }
    }

    /** True when a copy can be attempted at all: a router, a store and a graph folder exist and another graph is registered. */
    fun available(): Boolean = router() != null && store() != null && graphRoot() != null && targets().isNotEmpty()

    /** Copies [slug] to [target], as [asSlug] after a rename. Runs to its end even if the caller leaves, so no half-written bundle is abandoned. */
    suspend fun copy(slug: String, target: GraphId, asSlug: String = slug): Either<SketchCopyError, SketchCopyResult> = withContext(NonCancellable) {
        val writerRouter = router()
        val source = store()
        val root = graphRoot()
        if (writerRouter == null || source == null || root == null) return@withContext SketchCopyError.V2StorageUnavailable.left()
        val names = registry().graphs.associate { it.id to it.displayName }
        SketchBundleCopy(writerRouter, source, fileSystem, root, { source.v2StorageAvailable() }, { names[it] ?: it.value }).copy(slug, target, asSlug)
    }

    /** "Open" after a copy: switches to the graph that now holds the sketch. */
    fun open(target: SketchOpenTarget) = switchTo(target.graph)
}
