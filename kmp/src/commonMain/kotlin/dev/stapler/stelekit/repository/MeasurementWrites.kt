// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError

/**
 * The only way measurement-annotation writes reach [DatabaseWriteActor]: always
 * [DatabaseWriteActor.Priority.HIGH], because the actor has no ordering across priorities and a
 * LOW undo save could otherwise overtake a HIGH commit. [actor] null runs the op inline (tests).
 */
class MeasurementWrites(private val actor: DatabaseWriteActor?) {
    suspend fun execute(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        actor?.execute(DatabaseWriteActor.Priority.HIGH, true, op) ?: op()
}
