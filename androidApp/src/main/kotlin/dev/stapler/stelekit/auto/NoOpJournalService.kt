// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService

/**
 * A no-op [JournalService] stub injected into [dev.stapler.stelekit.voice.VoiceCaptureViewModel]
 * in the Auto context to suppress the VM's internal journal save.
 *
 * All writes are silently discarded to in-memory repositories that are not persisted.
 * [AudiobookNoteWriter] handles all actual persistence in the Auto context.
 */
fun NoOpJournalService(): JournalService = JournalService(
    pageRepository = InMemoryPageRepository(),
    blockRepository = InMemoryBlockRepository(),
)
