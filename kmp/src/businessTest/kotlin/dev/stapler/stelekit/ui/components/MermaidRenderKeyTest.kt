package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MermaidRenderKeyTest {
    @Test
    fun key_should_beEqual_when_allFieldsMatch() {
        val key1 = MermaidRenderKey("graph TD;A-->B", ThemeFingerprint(true, 1), 400)
        val key2 = MermaidRenderKey("graph TD;A-->B", ThemeFingerprint(true, 1), 400)
        assertEquals(key1, key2)
    }

    @Test
    fun key_should_beUnequal_when_themeDiffers() {
        val key1 = MermaidRenderKey("graph TD;A-->B", ThemeFingerprint(true, 1), 400)
        val key2 = key1.copy(theme = ThemeFingerprint(false, 1))
        assertNotEquals(key1, key2)
    }
}
