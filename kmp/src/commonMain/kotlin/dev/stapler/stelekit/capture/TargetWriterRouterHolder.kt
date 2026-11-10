// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.merge.TargetWriterRouter

/**
 * Hands the one [TargetWriterRouter] that `rememberCrossGraphUi` builds inside a Composable
 * `DisposableEffect` to non-Composable collaborators. It only publishes and clears: it never builds a router
 * (CLAUDE.md: one router, one lock). Null means this platform or host configured no share/copy pipeline.
 */
class TargetWriterRouterHolder {
    @kotlin.concurrent.Volatile
    private var router: TargetWriterRouter? = null

    fun get(): TargetWriterRouter? = router

    fun publish(value: TargetWriterRouter) {
        router = value
    }

    /** Clears only if [value] is still the published router, so a newer effect's router is not wiped. */
    fun clear(value: TargetWriterRouter) {
        if (router === value) router = null
    }
}
