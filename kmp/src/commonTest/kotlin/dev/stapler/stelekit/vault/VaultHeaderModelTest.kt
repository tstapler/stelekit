// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Layout arithmetic and value semantics of the on-disk vault header model. */
class VaultHeaderModelTest {
    private fun bytes(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    private fun keyslot(fill: Int = 1, params: Argon2Params = Argon2Params(memory = 1024, iterations = 2, parallelism = 1)) = Keyslot(
        salt = bytes(Keyslot.SALT_SIZE, fill),
        argon2Params = params,
        encryptedDekBlob = bytes(Keyslot.ENCRYPTED_BLOB_SIZE, fill),
        slotNonce = bytes(Keyslot.NONCE_SIZE, fill),
        reserved = bytes(Keyslot.RESERVED_SIZE, fill),
    )

    private fun header(slotFill: Int = 1, mac: Int = 9) = VaultHeader(
        randomPadding = bytes(VaultHeader.PADDING_SIZE, 3),
        keyslots = List(VaultHeader.KEYSLOT_COUNT) { keyslot(slotFill) },
        reserved = bytes(VaultHeader.RESERVED_SIZE, 4),
        hiddenHeaderMac = bytes(VaultHeader.MAC_SIZE, 5),
        headerMac = bytes(VaultHeader.MAC_SIZE, mac),
    )

    @Test
    fun keyslotFieldSizesSumToTheDeclaredSlotSize() {
        // salt + (memory u32, iterations u16, parallelism u16) + blob + nonce + reserved
        val params = 4 + 2 + 2
        assertEquals(
            Keyslot.TOTAL_SIZE,
            Keyslot.SALT_SIZE + params + Keyslot.ENCRYPTED_BLOB_SIZE + Keyslot.NONCE_SIZE + Keyslot.RESERVED_SIZE,
        )
    }

    @Test
    fun headerRegionSizesSumToTheDeclaredTotal() {
        val magicAndVersion = VaultHeader.MAGIC.size + 1
        assertEquals(
            VaultHeader.TOTAL_SIZE,
            magicAndVersion + VaultHeader.PADDING_SIZE + VaultHeader.KEYSLOT_COUNT * Keyslot.TOTAL_SIZE +
                VaultHeader.RESERVED_SIZE + 2 * VaultHeader.MAC_SIZE,
        )
    }

    @Test
    fun outerAndHiddenMacRangesCoverDisjointKeyslotHalves() {
        val half = VaultHeader.KEYSLOT_COUNT / 2

        assertEquals(VaultHeader.HEADER_PREFIX_SIZE, VaultHeader.MAGIC.size + 1 + VaultHeader.PADDING_SIZE)
        assertEquals(VaultHeader.HEADER_PREFIX_SIZE + half * Keyslot.TOTAL_SIZE, VaultHeader.OUTER_MAC_AUTH_SIZE)
        assertEquals(VaultHeader.OUTER_MAC_AUTH_SIZE, VaultHeader.HIDDEN_SLOT_START)
        assertEquals(VaultHeader.HEADER_PREFIX_SIZE + VaultHeader.KEYSLOT_COUNT * Keyslot.TOTAL_SIZE, VaultHeader.HIDDEN_SLOT_END)
        assertEquals(half * Keyslot.TOTAL_SIZE, VaultHeader.HIDDEN_SLOT_END - VaultHeader.HIDDEN_SLOT_START)
    }

    @Test
    fun magicSpellsSkvt() {
        assertEquals("SKVT", VaultHeader.MAGIC.decodeToString())
    }

    @Test
    fun headersWithEqualContentAreEqualAndHashAlike() {
        val a = header()
        val b = header()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a == a)
    }

    @Test
    fun headersDifferingInAnyAuthenticatedOrReservedFieldAreNotEqual() {
        val base = header()

        assertNotEquals(base, header(slotFill = 2))
        assertNotEquals(base, header(mac = 8))
        assertNotEquals(
            base,
            VaultHeader(
                version = 0x03, randomPadding = base.randomPadding, keyslots = base.keyslots, reserved = base.reserved,
                hiddenHeaderMac = base.hiddenHeaderMac, headerMac = base.headerMac,
            ),
        )
        assertNotEquals(
            base,
            VaultHeader(
                randomPadding = base.randomPadding, keyslots = base.keyslots, reserved = bytes(VaultHeader.RESERVED_SIZE, 0),
                hiddenHeaderMac = base.hiddenHeaderMac, headerMac = base.headerMac,
            ),
        )
        assertFalse(base.equals("not a header"))
    }

    @Test
    fun keyslotEqualityComparesByteContentAndArgonParams() {
        assertEquals(keyslot(1), keyslot(1))
        assertEquals(keyslot(1).hashCode(), keyslot(1).hashCode())
        assertNotEquals(keyslot(1), keyslot(2))
        assertNotEquals(keyslot(1), keyslot(1, Argon2Params(memory = 2048, iterations = 2, parallelism = 1)))
        assertFalse(keyslot().equals(null))
    }

    @Test
    fun namespaceTagsRoundTripAndUnknownTagsAreRejected() {
        VaultNamespace.entries.forEach { assertEquals(it, VaultNamespace.fromTag(it.tag)) }

        val error = assertFailsWith<VaultAuthException> { VaultNamespace.fromTag(0x7F) }
        assertTrue("0x7f" in error.message.orEmpty(), error.message)
    }
}
