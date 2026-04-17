// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page

fun migration(id: String, configure: MigrationBuilder.() -> Unit): Migration {
    val builder = MigrationBuilder(id)
    builder.configure()
    return builder.build()
}

class MigrationBuilder(val id: String) {
    var description: String = ""
    var checksumBody: String = ""
    var allowDestructive: Boolean = false
    val requires: MutableList<String> = mutableListOf()
    val conflicts: MutableList<String> = mutableListOf()
    private var applyLambda: (MigrationScope.() -> Unit)? = null
    private var revertLambda: (MigrationScope.() -> Unit)? = null

    fun apply(block: MigrationScope.() -> Unit) { applyLambda = block }
    fun revert(block: MigrationScope.() -> Unit) { revertLambda = block }
    fun requires(vararg ids: String) { requires.addAll(ids) }
    fun conflicts(vararg ids: String) { conflicts.addAll(ids) }

    fun build(): Migration {
        val body = checksumBody.ifEmpty { id }
        return Migration(
            id = id,
            description = description,
            checksumBody = body,
            requires = requires.toList(),
            conflicts = conflicts.toList(),
            allowDestructive = allowDestructive,
            apply = applyLambda ?: {},
            revert = revertLambda,
        )
    }
}

/** No-op implementation of [MigrationScope] used for checksumBody computation. */
private class NoOpMigrationScope : MigrationScope {
    override fun forBlocks(where: (Block) -> Boolean, transform: BlockScope.() -> Unit) {}
    override fun forPages(where: (Page) -> Boolean, transform: PageScope.() -> Unit) {}
}
