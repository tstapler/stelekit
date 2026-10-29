// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the confirm modal shows for one `sql!` write. */
data class ConfirmRequest(
    val title: String,
    /** The statement, verbatim. */
    val statement: String,
    /** Plain-language lines under the statement: the count, extra tables, the backup. */
    val details: List<String>,
    val tier: ConfirmTier,
    /** Verb-and-count label for the primary button, for example "Update 7 rows". */
    val primaryLabel: String,
)

sealed interface ConfirmAnswer {
    /** The default: dismissing the modal or cancelling the command lands here. */
    data object Cancel : ConfirmAnswer

    /** [typed] is what the owner entered for a [ConfirmTier.TypedCount] confirm, otherwise null. */
    data class Proceed(val typed: String? = null) : ConfirmAnswer
}

/** True when [answer] satisfies [tier]: a typed count must match exactly after trimming surrounding spaces. */
fun ConfirmAnswer.satisfies(tier: ConfirmTier): Boolean = when (this) {
    ConfirmAnswer.Cancel -> false
    is ConfirmAnswer.Proceed -> when (tier) {
        ConfirmTier.Standard -> true
        is ConfirmTier.TypedCount -> typed?.trim() == tier.count.toString()
    }
}

/** Asks the owner to confirm; implemented by the console session, which shows the modal. */
fun interface ConsoleConfirmer {
    suspend fun confirm(request: ConfirmRequest): ConfirmAnswer
}

/** A confirmer for contexts with no UI: every request is cancelled. */
object NoConfirmer : ConsoleConfirmer {
    override suspend fun confirm(request: ConfirmRequest): ConfirmAnswer = ConfirmAnswer.Cancel
}

/** Writes confirmed this session, newest last. They are DB-only; `diag` lists them. */
class ConsoleWriteLog {
    private val items = MutableStateFlow<List<String>>(emptyList())
    val entries: List<String> get() = items.value

    fun record(entry: String) = items.update { it + entry }
}
