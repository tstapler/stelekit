// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.projectbridge.newBridgeTestRouter
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class TargetWriterRouterHolderTest {
    @Test
    fun isEmptyUntilARouterIsPublished() {
        assertNull(TargetWriterRouterHolder().get())
    }

    @Test
    fun publishThenClear_RoundTrips() {
        val holder = TargetWriterRouterHolder()
        val router = newBridgeTestRouter()
        holder.publish(router)
        assertSame(router, holder.get())
        holder.clear(router)
        assertNull(holder.get())
    }

    @Test
    fun clear_IgnoresARouterThatWasAlreadyReplaced() {
        val holder = TargetWriterRouterHolder()
        val old = newBridgeTestRouter()
        val newer = newBridgeTestRouter()
        holder.publish(old)
        holder.publish(newer)
        holder.clear(old)
        assertSame(newer, holder.get())
    }
}
