package dev.stapler.stelekit.util

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlin.time.Clock

object TestUtils {
    // Helper to pad UUID segments
    private fun pad(s: String, length: Int): String = s.padStart(length, '0')

    fun createBlock(
        uuid: String = "00000000-0000-0000-0000-000000000001",
        pageUuid: String = "page-1",
        parentUuid: String? = null,
        content: String = "",
        level: Int = 0,
        position: String = "a0"
    ): Block {
        val now = Clock.System.now()
        return Block(
            uuid = BlockUuid(uuid),
            pageUuid = PageUuid(pageUuid),
            parentUuid = parentUuid?.let { BlockUuid(it) },
            leftUuid = null,
            content = content,
            level = level,
            position = position,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap()
        )
    }
}
