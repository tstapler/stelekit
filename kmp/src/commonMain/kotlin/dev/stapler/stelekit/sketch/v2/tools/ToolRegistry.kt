// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

/**
 * Maps each [ToolId] to the factory of its Idle state. Immutable: registering returns a new registry, so a
 * composition root builds it once. Tools that have not landed yet are simply absent from [registered]; the
 * inventory itself ([ToolId.entries]) is fixed.
 */
class ToolRegistry private constructor(private val factories: Map<ToolId, () -> Tool>) {
    val registered: Set<ToolId> get() = factories.keys

    /** A fresh Idle tool for [id], or null when that tool is not registered. */
    fun create(id: ToolId): Tool? = factories[id]?.invoke()

    fun with(id: ToolId, factory: () -> Tool): ToolRegistry = ToolRegistry(factories + (id to factory))

    /** Inventory members with no registered tool. */
    fun missing(): List<ToolId> = ToolId.entries.filter { it !in factories }

    companion object {
        val Empty = ToolRegistry(emptyMap())
    }
}
