// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Per-project rebuild counter shared by the three sketch repositories of one graph; in memory only. */
class SketchRevisionTokens {
    private val counters = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun current(project: ProjectUuid): RevisionToken = RevisionToken(project, counters.value[project.value] ?: 0L)

    fun bump(project: ProjectUuid) = counters.update { it + (project.value to ((it[project.value] ?: 0L) + 1)) }

    fun forget(project: ProjectUuid) = counters.update { it - project.value }

    fun check(token: RevisionToken): Either<DomainError, Unit> =
        if (current(token.projectUuid).value == token.value) Unit.right()
        else DomainError.DatabaseError.WriteFailed(STALE_EDITOR).left()

    companion object {
        const val STALE_EDITOR = "stale-editor"
    }
}
