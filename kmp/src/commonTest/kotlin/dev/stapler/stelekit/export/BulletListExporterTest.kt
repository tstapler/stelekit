package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class BulletListExporterTest {
    private val now = Clock.System.now()
    private val exporter = BulletListExporter()
    private val page = Page(uuid = PageUuid("p"), name = "P", createdAt = now, updatedAt = now)

    private fun block(content: String, level: Int, position: String, uuid: String, parent: String? = null) = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid("p"), parentUuid = parent?.let { BlockUuid(it) },
        content = content, level = level, position = position, createdAt = now, updatedAt = now,
    )

    @Test
    fun formatIdIsBulletList() = assertEquals("bullet-list", exporter.formatId)

    @Test
    fun flattensNestedBlocksIntoBulletLines() {
        val out = exporter.export(
            page,
            listOf(
                block("one", 0, "a0", "b1"),
                block("child **bold** [[Link]]", 1, "a0", "b2", "b1"),
                block("two", 0, "a1", "b3"),
            ),
            emptyMap(),
        )
        assertEquals("• one\n• child bold Link\n• two", out)
    }
}
