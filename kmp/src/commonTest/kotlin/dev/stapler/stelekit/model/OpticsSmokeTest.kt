package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OpticsSmokeTest {

    private fun now(): Instant = Instant.fromEpochMilliseconds(0)

    private fun makePage(name: String = "test") = Page(
        uuid = "page-1",
        name = name,
        createdAt = now(),
        updatedAt = now(),
    )

    private fun makeBlock(uuid: String = "block-1", content: String = "hello") = Block(
        uuid = uuid,
        pageUuid = "page-1",
        content = content,
        position = 0,
        createdAt = now(),
        updatedAt = now(),
    )

    @Test
    fun page_name_lens_get() {
        val page = makePage("My Page")
        assertEquals("My Page", PageOptics.name.get(page))
    }

    @Test
    fun page_name_lens_modify() {
        val page = makePage("My Page")
        val result = PageOptics.name.modify(page) { it.uppercase() }
        assertEquals("MY PAGE", result.name)
    }

    @Test
    fun page_is_favorite_lens_set() {
        val page = makePage()
        val result = PageOptics.isFavorite.set(page, true)
        assertEquals(true, result.isFavorite)
    }

    @Test
    fun block_content_lens_modify() {
        val block = makeBlock(content = "hello world")
        val result = BlockOptics.content.modify(block) { it.trim() }
        assertEquals("hello world", result.content)
    }

    @Test
    fun block_version_lens_set() {
        val block = makeBlock()
        val updated = BlockOptics.version.set(block, 42L)
        assertEquals(42L, updated.version)
    }

    @Test
    fun property_key_lens_get() {
        val prop = Property(
            uuid = "prop-1",
            blockUuid = "block-1",
            key = "type",
            value = "todo",
            createdAt = now(),
        )
        assertEquals("type", PropertyOptics.key.get(prop))
    }

    @Test
    fun lens_composition_page_name_uppercase() {
        val pages = listOf(makePage("alpha"), makePage("beta"))
        val result = pages.map { PageOptics.name.modify(it) { n -> n.replaceFirstChar { c -> c.uppercase() } } }
        assertEquals(listOf("Alpha", "Beta"), result.map { it.name })
    }
}
