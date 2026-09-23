package dev.stapler.stelekit.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Crc32Test {

    @Test
    fun crc32Of_should_ReturnStandardCheckValue_When_GivenKnownTestVector() {
        val bytes = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926.toInt(), Crc32.of(bytes))
    }

    @Test
    fun crc32Of_should_ReturnDifferentValue_When_SingleByteFlipped() {
        val original = "123456789".encodeToByteArray()
        val tampered = original.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        assertNotEquals(Crc32.of(original), Crc32.of(tampered))
    }
}
