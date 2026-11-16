// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4.3: the image element holds the fileId, is centred on the click and scaled down to a bounded size. */
class ImageToolTest {
    private val picked = PickedImage("a".repeat(64), widthPx = 1024, heightPx = 512)

    @Test
    fun click_should_PlaceCentredScaledImage_HoldingOnlyTheFileId() {
        val r = runScenario(ImageTool(picked)) { down(100, 100); up(100, 100) }
        val image = assertIs<ImageElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(picked.fileId, image.fileId)
        assertEquals(ImageTool.MAX_PLACED_SIDE_UNITS, image.width.value)
        assertEquals(ImageTool.MAX_PLACED_SIDE_UNITS / 2, image.height.value)
        assertEquals(100.0 - image.width.value / 2, image.x.value)
        assertEquals(100.0 - image.height.value / 2, image.y.value)
    }

    @Test
    fun smallImage_should_NotBeScaledUp() {
        val r = runScenario(ImageTool(PickedImage(picked.fileId, 40, 20))) { down(0, 0); up(0, 0) }
        val image = assertIs<ImageElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(40.0, image.width.value)
    }

    @Test
    fun click_should_RequestAPick_When_NothingIsPicked_And_SpendThePickAfterPlacing() {
        val none = runScenario(ImageTool()) { down(5, 6); up(5, 6) }
        val request = assertIs<RequestImagePickCommand>(none.commands.single())
        assertEquals(5.0, request.at.x.value)

        val placed = runScenario(ImageTool(picked)) { down(0, 0); up(0, 0); down(1, 1); up(1, 1) }
        assertIs<AddElementCommand>(placed.commands[0])
        assertIs<RequestImagePickCommand>(placed.commands[1])
    }

    @Test
    fun cancel_should_PlaceNothing_And_KeepThePickArmed() {
        val r = runScenario(ImageTool(picked)) { down(0, 0); escape(); up(0, 0); down(2, 2); down(3, 3, pointer = 1); up(2, 2); down(4, 4); up(4, 4) }
        assertEquals(1, r.commands.size)
        assertIs<AddElementCommand>(r.commands.single())
        assertNull(r.previews.last())
        assertTrue(r.finalTool.id == ToolId.Image)
    }
}
