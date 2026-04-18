// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page

@DslMarker
annotation class MigrationDslMarker

@MigrationDslMarker
interface MigrationScope {
    fun forBlocks(where: (Block) -> Boolean, transform: BlockScope.() -> Unit)
    fun forPages(where: (Page) -> Boolean, transform: PageScope.() -> Unit)
    fun findPage(name: String): Page?
}

@MigrationDslMarker
interface BlockScope {
    val block: Block
    fun setProperty(key: String, value: String)
    fun deleteProperty(key: String)
    fun setContent(newContent: String)
    fun deleteBlock()  // only valid if migration.allowDestructive = true
}

@MigrationDslMarker
interface PageScope {
    val page: Page
    fun setProperty(key: String, value: String)
    fun deleteProperty(key: String)
    fun renamePage(newName: String)
    fun deletePage()  // only valid if migration.allowDestructive = true
    /**
     * Upserts non-empty blocks onto [targetPageUuid] (preserving UUIDs and intra-tree refs),
     * deletes empty blocks, offsets root-level block positions past existing target content,
     * then deletes this page. Only valid if migration.allowDestructive = true.
     */
    fun mergeIntoPage(targetPageUuid: String)
}
