// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

interface NoteWriter {
    suspend fun writeNote(note: AudiobookNote): Either<DomainError, Unit>
}
