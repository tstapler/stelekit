// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block

sealed class BlockChange {
    data class UpsertProperty(val blockUuid: String, val key: String, val value: String) : BlockChange()
    data class DeleteProperty(val blockUuid: String, val key: String) : BlockChange()
    data class SetContent(val blockUuid: String, val newContent: String) : BlockChange()
    data class DeleteBlock(val blockUuid: String) : BlockChange()
    data class InsertBlock(val block: Block) : BlockChange()
    data class UpsertPageProperty(val pageUuid: String, val key: String, val value: String) : BlockChange()
    data class DeletePageProperty(val pageUuid: String, val key: String) : BlockChange()
    data class RenamePage(val pageUuid: String, val oldName: String, val newName: String) : BlockChange()
    data class DeletePage(val pageUuid: String) : BlockChange()
}
