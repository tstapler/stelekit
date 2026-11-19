// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The saver's view of "has this write reached storage" (Story 1.4.7, D-119). A write is accepted when the store returns;
 * it is persisted when this port says so. On desktop and Android the two are the same moment, so [ImmediateSketchDurability]
 * answers at once; on the web it waits for the OPFS operations queued for the sheet's files.
 */
interface SketchDurability {
    /** Completes when the sheet file [relPath] and the model of project [slug] have reached storage, or says why not. */
    suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit>
}

/** Synchronous platforms: a returned write is already durable. */
object ImmediateSketchDurability : SketchDurability {
    override suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit> = Unit.right()
}

/** [SketchDurability] over [FileSystem.awaitDurable]; [graphRoot] is the notes root of the active graph, null when none is open. */
class FileSystemSketchDurability(
    private val fs: FileSystem,
    private val graphRoot: () -> String?,
) : SketchDurability {
    override suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit> {
        val root = graphRoot() ?: return Unit.right()
        val dir = "${root.trimEnd('/')}/${FileProjectSidecarStore.ROOT_DIR}/$slug"
        return fs.awaitDurable("$dir/$relPath").flatMap { fs.awaitDurable("$dir/${SidecarCodec.MODEL_PATH}") }
    }
}

/**
 * The page-hidden signal of the web build (`visibilitychange` to hidden): each emission asks every open saver to skip its
 * debounce and write now. Platforms without a page lifecycle use [NoPageLifecycle].
 */
interface PageLifecycle {
    val hidden: Flow<Unit>
}

object NoPageLifecycle : PageLifecycle {
    override val hidden: Flow<Unit> = emptyFlow()
}
