// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import kotlin.test.Test
import kotlin.test.assertEquals

/** A toggle's toast replaces its own previous toast; before, "Snap on" and "Snap off" stacked as two rows. */
class NoticeReplaceTest {
    private fun toast(key: String, text: String) = SketchNotice(key, BannerTier.Info, text)

    @Test
    fun postReplacing_should_ShowOnlyTheLatest_When_TheSameKeyChangesText() {
        val q = SketchNoticeQueue()
        q.postReplacing(toast("ViewSnap", "Snap on"), now = 0)
        q.postReplacing(toast("ViewSnap", "Snap off"), now = 10)
        assertEquals(listOf("Snap off"), q.visible(now = 20).map { it.notice.text })
    }

    @Test
    fun postReplacing_should_Coalesce_When_TheTextIsIdentical() {
        val q = SketchNoticeQueue()
        q.postReplacing(toast("ViewSnap", "Snap on"), now = 0)
        q.postReplacing(toast("ViewSnap", "Snap on"), now = 10)
        assertEquals(listOf("Snap on" to 2), q.visible(now = 20).map { it.notice.text to it.count })
    }

    @Test
    fun postReplacing_should_KeepOtherKeys() {
        val q = SketchNoticeQueue()
        q.postReplacing(toast("ViewGrid", "Grid on"), now = 0)
        q.postReplacing(toast("ViewSnap", "Snap on"), now = 5)
        q.postReplacing(toast("ViewSnap", "Snap off"), now = 10)
        assertEquals(setOf("Grid on", "Snap off"), q.visible(now = 20).map { it.notice.text }.toSet())
    }

    @Test
    fun post_should_StillStack_ForPlainPosts_SoThePinnedQueueRulesHold() {
        val q = SketchNoticeQueue()
        q.post(toast("k", "one"), now = 0)
        q.post(toast("k", "two"), now = 10)
        assertEquals(2, q.visible(now = 20).size)
    }
}
