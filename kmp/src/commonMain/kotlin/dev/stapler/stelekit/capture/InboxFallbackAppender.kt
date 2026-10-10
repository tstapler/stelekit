// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.util.UuidGenerator

/**
 * Decorates the off-graph route: a [AppendOutcome.Deferred] result (permission, not round-trippable,
 * retry-exhausted, ...) is enqueued into [ShareInbox] and reported as [AppendOutcome.Queued], so a
 * share is never lost. No router or lock logic lives here; that stays in [RouterOffGraphRoute].
 *
 * The image, if any, is copied to app-private storage by [ShareInbox.enqueue] at enqueue time.
 */
class InboxFallbackAppender(
    private val delegate: OffGraphContentRoute,
    private val inbox: ShareInbox,
) : OffGraphContentRoute {

    override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome {
        val id = captureId ?: UuidGenerator.generateV7()
        val outcome = delegate.appendContent(graphId, content, id)
        if (outcome !is AppendOutcome.Deferred) return outcome
        return inbox.enqueue(InboxSlot.Graph(graphId), content, id).fold(
            { AppendOutcome.Failed("Could not save or queue the share: ${it.message}") },
            { AppendOutcome.Queued(outcome.reason) },
        )
    }
}

/** Adapts a non-queuing [JournalAppender] to the drain's [InboxAppender]; the drain must never re-enqueue. */
class JournalInboxAppender(private val appender: JournalAppender) : InboxAppender {
    override suspend fun append(graphId: GraphId, content: ShareContent, captureId: String): DrainAppendResult =
        when (val outcome = appender.appendContent(CaptureTarget.NamedGraph(graphId), content, captureId)) {
            is AppendOutcome.Appended, is AppendOutcome.AppendedOffGraph -> DrainAppendResult.Appended
            AppendOutcome.AlreadyPresent -> DrainAppendResult.AlreadyPresent
            is AppendOutcome.Queued -> DrainAppendResult.Retry(outcome.reason)
            is AppendOutcome.Deferred ->
                if (outcome.permanent) DrainAppendResult.Failed(outcome.reason) else DrainAppendResult.Retry(outcome.reason)
            is AppendOutcome.Failed -> DrainAppendResult.Failed(outcome.error)
        }
}
