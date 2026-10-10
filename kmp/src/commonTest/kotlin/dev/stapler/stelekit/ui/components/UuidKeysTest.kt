package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UuidKeysTest {

    @Test
    fun `PageUuid asLazyKey returns the underlying string value`() {
        assertEquals("abc-123", PageUuid("abc-123").asLazyKey())
    }

    @Test
    fun `BlockUuid asLazyKey returns the underlying string value`() {
        assertEquals("def-456", BlockUuid("def-456").asLazyKey())
    }

    @Test
    fun `distinct PageUuid values produce distinct keys`() {
        assertNotEquals(PageUuid("x").asLazyKey(), PageUuid("y").asLazyKey())
    }

    @Test
    fun `distinct BlockUuid values produce distinct keys`() {
        assertNotEquals(BlockUuid("a").asLazyKey(), BlockUuid("b").asLazyKey())
    }

    @Test
    fun `asLazyKey is identical to value`() {
        val uuid = PageUuid("019eb80f-bc1b-77b1-856e-afb6dab2fc0f")
        assertEquals(uuid.value, uuid.asLazyKey())
    }

    @Test
    fun `PageUuid and BlockUuid with same string produce same key string`() {
        // Keys from different value class types with the same underlying value
        // must compare equal as strings — LazyColumn uses equals() on keys.
        assertEquals(PageUuid("same-id").asLazyKey(), BlockUuid("same-id").asLazyKey())
    }
}
