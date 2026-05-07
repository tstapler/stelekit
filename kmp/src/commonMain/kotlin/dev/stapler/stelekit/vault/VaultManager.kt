package dev.stapler.stelekit.vault

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Manages vault header lifecycle: create, unlock, lock, keyslot add/remove.
 *
 * The DEK is held in memory only while the vault is unlocked. Calling [lock] zero-fills
 * the DEK array and emits [VaultEvent.Locked].
 *
 * Argon2id key derivation runs on [Dispatchers.Default] (CPU-bound, ~350ms–1s per slot).
 * All 8 keyslots are always tried during unlock in constant order — no plaintext hint
 * is used to skip slots, preserving deniability for hidden-volume passphrases.
 */
class VaultManager(
    private val crypto: CryptoEngine,
    private val fileReadBytes: (path: String) -> ByteArray?,
    private val fileWriteBytes: (path: String, data: ByteArray) -> Boolean,
) {
    // DROP_OLDEST ensures tryEmit always succeeds from non-suspend lock(); Locked is never silently dropped.
    private val _vaultEvents = MutableSharedFlow<VaultEvent>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val vaultEvents: SharedFlow<VaultEvent> = _vaultEvents.asSharedFlow()

    // @Volatile so that lock() on any thread is immediately visible to currentDek() callers.
    @Volatile private var sessionDek: ByteArray? = null
    @Volatile private var sessionNamespace: VaultNamespace? = null

    sealed interface VaultEvent {
        data object Locked : VaultEvent
        data class Unlocked(val namespace: VaultNamespace) : VaultEvent
    }

    data class UnlockResult(val dek: ByteArray, val namespace: VaultNamespace)

    /**
     * Create a new vault at [graphPath]/.stele-vault with a single passphrase keyslot.
     *
     * [argon2Params] defaults to [DEFAULT_ARGON2_PARAMS]; supply a calibrated set for production use.
     */
    suspend fun createVault(
        graphPath: String,
        passphrase: CharArray,
        namespace: VaultNamespace = VaultNamespace.OUTER,
        argon2Params: Argon2Params = DEFAULT_ARGON2_PARAMS,
    ): Either<VaultError, ByteArray> = withContext(Dispatchers.Default) {
        try {
            val dek = crypto.secureRandom(32)
            val slotIndex = namespaceFirstSlot(namespace)
            val keyslot = buildKeyslot(passphrase, dek, namespace, argon2Params, slotIndex)

            val allSlots = (0 until VaultHeader.KEYSLOT_COUNT).map { i ->
                if (i == slotIndex) keyslot else randomSlot()
            }

            val padding = crypto.secureRandom(VaultHeader.PADDING_SIZE)
            val reserved = crypto.secureRandom(VaultHeader.RESERVED_SIZE)

            val macKey = deriveHeaderMacKey(dek)
            val header = VaultHeader(
                randomPadding = padding,
                keyslots = allSlots,
                reserved = reserved,
                headerMac = ByteArray(VaultHeader.MAC_SIZE),
            )
            val partialBytes = VaultHeaderSerializer.serialize(header)
            val mac = computeHeaderMac(macKey, partialBytes.sliceArray(0 until VaultHeader.MAC_AUTHENTICATED_SIZE))
            crypto.clearBytes(macKey)
            val finalHeader = header.copy(headerMac = mac)
            val headerBytes = VaultHeaderSerializer.serialize(finalHeader)

            val vaultPath = vaultFilePath(graphPath)
            if (!fileWriteBytes(vaultPath, headerBytes)) {
                return@withContext VaultError.CorruptedFile("Failed to write vault file to $vaultPath").left()
            }

            // Pre-create the hidden-reserve directory so it exists even before a hidden graph is written.
            // Written as a random-byte sentinel — indistinguishable from encrypted file content.
            val reservePath = hiddenReserveSentinelPath(graphPath)
            if (!fileWriteBytes(reservePath, crypto.secureRandom(256))) {
                // Non-fatal: the reserve sentinel is best-effort infrastructure.
                // Log-worthy but not a reason to fail vault creation.
            }

            dek.right()
        } finally {
            passphrase.fill(' ')
        }
    }

    /**
     * Try all 8 keyslots in constant-time order, returning the DEK and namespace if any match.
     * All slots are always tried — no plaintext skip — to preserve deniability.
     * The passphrase CharArray is zero-filled after derivation regardless of outcome.
     */
    suspend fun unlock(
        graphPath: String,
        passphrase: CharArray,
        argon2Params: Argon2Params? = null,
    ): Either<VaultError, UnlockResult> = withContext(Dispatchers.Default) {
        val vaultPath = vaultFilePath(graphPath)
        val rawBytes = withContext(Dispatchers.IO) { fileReadBytes(vaultPath) }
            ?: return@withContext VaultError.NotAVault("Vault file not found at $vaultPath").left()

        val header = when (val r = VaultHeaderSerializer.deserialize(rawBytes)) {
            is Either.Left -> return@withContext r
            is Either.Right -> r.value
        }

        val passwordBytes = passphrase.toByteArray()
        try {
            var validDek: ByteArray? = null
            var validNamespace: VaultNamespace? = null
            // Tracks whether a slot decrypted successfully but the header MAC failed,
            // which means the correct passphrase was used but the vault was tampered.
            var macFailed = false

            // Try all 8 slots in order — no plaintext hint skips any slot.
            // Decoy slots fail AEAD decryption (expected), active slots succeed.
            for ((index, slot) in header.keyslots.withIndex()) {
                val params = argon2Params ?: slot.argon2Params
                val keyslotKey = crypto.argon2id(
                    password = passwordBytes,
                    salt = slot.salt,
                    memory = params.memory,
                    iterations = params.iterations,
                    parallelism = params.parallelism,
                    outputLength = 32,
                )
                try {
                    val plaintext = crypto.decryptAEAD(keyslotKey, slot.slotNonce, slot.encryptedDekBlob, byteArrayOf())
                    // plaintext = DEK (32 bytes) + namespace_tag (1 byte) + provider_type (1 byte)
                    if (plaintext.size == 34 && validDek == null) {
                        val dek = plaintext.sliceArray(0 until 32)
                        val ns = try {
                            VaultNamespace.fromTag(plaintext[32])
                        } catch (_: VaultAuthException) {
                            crypto.clearBytes(dek)
                            crypto.clearBytes(plaintext)
                            continue
                        }
                        // Verify header MAC using the recovered DEK
                        val macKey = deriveHeaderMacKey(dek)
                        val expectedMac = computeHeaderMac(
                            macKey,
                            rawBytes.sliceArray(0 until VaultHeader.MAC_AUTHENTICATED_SIZE)
                        )
                        crypto.clearBytes(macKey)
                        if (constantTimeEquals(expectedMac, header.headerMac)) {
                            validDek = dek
                            validNamespace = ns
                        } else {
                            macFailed = true
                            crypto.clearBytes(dek)
                        }
                        crypto.clearBytes(expectedMac)
                    }
                    crypto.clearBytes(plaintext)
                } catch (_: VaultAuthException) {
                    // Expected for decoy slots and wrong-passphrase slots — continue.
                }
                crypto.clearBytes(keyslotKey)
            }

            if (validDek == null || validNamespace == null) {
                if (validDek != null) crypto.clearBytes(validDek)
                return@withContext if (macFailed) {
                    VaultError.HeaderTampered().left()
                } else {
                    VaultError.InvalidCredential().left()
                }
            }

            sessionDek = validDek
            sessionNamespace = validNamespace
            _vaultEvents.tryEmit(VaultEvent.Unlocked(validNamespace))
            UnlockResult(dek = validDek, namespace = validNamespace).right()
        } finally {
            crypto.clearBytes(passwordBytes)
            passphrase.fill(' ')
        }
    }

    /**
     * Zero-fill the in-memory DEK and emit [VaultEvent.Locked].
     * Null is written before zeroing so concurrent [currentDek] callers see null immediately.
     */
    fun lock() {
        val dek = sessionDek
        sessionDek = null       // visible immediately to other threads (@Volatile)
        sessionNamespace = null
        dek?.let { crypto.clearBytes(it) }
        _vaultEvents.tryEmit(VaultEvent.Locked)
    }

    /** Returns the current in-memory DEK (null when locked). */
    fun currentDek(): ByteArray? = sessionDek

    /**
     * Add a new passphrase keyslot to an existing vault.
     * The caller must first unlock with an existing provider to supply [dek].
     */
    suspend fun addKeyslot(
        graphPath: String,
        dek: ByteArray,
        passphrase: CharArray,
        namespace: VaultNamespace = VaultNamespace.OUTER,
        argon2Params: Argon2Params = DEFAULT_ARGON2_PARAMS,
    ): Either<VaultError, Unit> = withContext(Dispatchers.Default) {
        try {
            val vaultPath = vaultFilePath(graphPath)
            val rawBytes = withContext(Dispatchers.IO) { fileReadBytes(vaultPath) }
                ?: return@withContext VaultError.NotAVault("Vault file not found").left()
            val header = when (val r = VaultHeaderSerializer.deserialize(rawBytes)) {
                is Either.Left -> return@withContext r
                is Either.Right -> r.value
            }

            // Verify the provided DEK is correct before mutating any slots.
            // Without this check, a caller with a wrong DEK could overwrite active slots
            // because the marker comparison would fail for all slots (false negatives).
            val verifyMacKey = deriveHeaderMacKey(dek)
            val actualMac = computeHeaderMac(verifyMacKey, rawBytes.sliceArray(0 until VaultHeader.MAC_AUTHENTICATED_SIZE))
            crypto.clearBytes(verifyMacKey)
            val dekValid = constantTimeEquals(actualMac, header.headerMac)
            crypto.clearBytes(actualMac)
            if (!dekValid) {
                return@withContext VaultError.InvalidCredential("Provided DEK does not match vault header").left()
            }

            val targetSlots = namespaceSlotRange(namespace)
            // A slot is "mine" if its reserved[0..3] matches the 4-byte DEK-derived marker.
            // Slots without the marker (decoy slots) are safe to overwrite.
            val emptySlotIndex = targetSlots.firstOrNull { index ->
                !isSlotMine(header.keyslots[index], dek, index)
            } ?: return@withContext VaultError.SlotsFull().left()

            val newSlot = buildKeyslot(passphrase, dek, namespace, argon2Params, emptySlotIndex)
            val updatedSlots = header.keyslots.toMutableList()
            updatedSlots[emptySlotIndex] = newSlot

            writeUpdatedHeader(vaultPath, dek, header.copy(keyslots = updatedSlots))
        } finally {
            passphrase.fill(' ')
        }
    }

    /**
     * Overwrite keyslot at [slotIndex] with random bytes (effectively removing it).
     * The DEK is not re-encrypted — remaining providers can still unlock.
     */
    suspend fun removeKeyslot(
        graphPath: String,
        slotIndex: Int,
    ): Either<VaultError, Unit> = withContext(Dispatchers.Default) {
        val vaultPath = vaultFilePath(graphPath)
        val rawBytes = withContext(Dispatchers.IO) { fileReadBytes(vaultPath) }
            ?: return@withContext VaultError.NotAVault("Vault file not found").left()
        val header = when (val r = VaultHeaderSerializer.deserialize(rawBytes)) {
            is Either.Left -> return@withContext r
            is Either.Right -> r.value
        }
        val dek = sessionDek ?: return@withContext VaultError.InvalidCredential("Vault is locked").left()

        val updatedSlots = header.keyslots.toMutableList()
        updatedSlots[slotIndex] = randomSlot()
        writeUpdatedHeader(vaultPath, dek, header.copy(keyslots = updatedSlots))
    }

    private fun writeUpdatedHeader(
        vaultPath: String,
        dek: ByteArray,
        header: VaultHeader,
    ): Either<VaultError, Unit> {
        val partialBytes = VaultHeaderSerializer.serialize(header.copy(headerMac = ByteArray(VaultHeader.MAC_SIZE)))
        val macKey = deriveHeaderMacKey(dek)
        val mac = computeHeaderMac(macKey, partialBytes.sliceArray(0 until VaultHeader.MAC_AUTHENTICATED_SIZE))
        crypto.clearBytes(macKey)
        val finalHeader = header.copy(headerMac = mac)
        val headerBytes = VaultHeaderSerializer.serialize(finalHeader)
        return if (fileWriteBytes(vaultPath, headerBytes)) {
            Unit.right()
        } else {
            VaultError.CorruptedFile("Failed to write updated vault header").left()
        }
    }

    private fun buildKeyslot(
        passphrase: CharArray,
        dek: ByteArray,
        namespace: VaultNamespace,
        argon2Params: Argon2Params,
        slotIndex: Int,
    ): Keyslot {
        val salt = crypto.secureRandom(Keyslot.SALT_SIZE)
        val passwordBytes = passphrase.toByteArray()
        var keyslotKey = byteArrayOf()
        var plaintext = byteArrayOf()
        var markerKey = byteArrayOf()
        try {
            keyslotKey = crypto.argon2id(
                password = passwordBytes,
                salt = salt,
                memory = argon2Params.memory,
                iterations = argon2Params.iterations,
                parallelism = argon2Params.parallelism,
                outputLength = 32,
            )

            // plaintext = DEK (32 bytes) + namespace_tag (1 byte) + provider_type (1 byte)
            plaintext = dek + byteArrayOf(namespace.tag, Keyslot.PROVIDER_PASSPHRASE)
            val nonce = crypto.secureRandom(Keyslot.NONCE_SIZE)
            val blob = crypto.encryptAEAD(keyslotKey, nonce, plaintext, byteArrayOf())

            // reserved[0..3]: 4-byte DEK-derived marker so addKeyslot can find owned slots
            // without a plaintext providerType byte (1/2^32 false-positive rate for decoy slots).
            // Adversaries without the DEK cannot verify this marker — deniability is preserved.
            val reserved = crypto.secureRandom(Keyslot.RESERVED_SIZE)
            markerKey = crypto.hkdfSha256(
                ikm = dek,
                salt = "slot-marker-v1".encodeToByteArray(),
                info = byteArrayOf(slotIndex.toByte()),
                length = 4,
            )
            reserved[0] = markerKey[0]; reserved[1] = markerKey[1]
            reserved[2] = markerKey[2]; reserved[3] = markerKey[3]

            return Keyslot(
                salt = salt,
                argon2Params = argon2Params,
                encryptedDekBlob = blob,
                slotNonce = nonce,
                reserved = reserved,
            )
        } finally {
            crypto.clearBytes(passwordBytes)
            crypto.clearBytes(keyslotKey)
            crypto.clearBytes(plaintext)
            crypto.clearBytes(markerKey)
        }
    }

    private fun randomSlot(): Keyslot = Keyslot(
        salt = crypto.secureRandom(Keyslot.SALT_SIZE),
        argon2Params = DEFAULT_ARGON2_PARAMS,
        encryptedDekBlob = crypto.secureRandom(Keyslot.ENCRYPTED_BLOB_SIZE),
        slotNonce = crypto.secureRandom(Keyslot.NONCE_SIZE),
        reserved = crypto.secureRandom(Keyslot.RESERVED_SIZE),
    )

    /**
     * A slot is "mine" if [reserved][0..3] matches the 4-byte HKDF marker derived from [dek] and [slotIndex].
     * Decoy slots have random reserved bytes; the probability of a false positive is 1/2^32.
     * An adversary without the DEK cannot verify this marker, so it reveals nothing about active slots.
     */
    private fun isSlotMine(slot: Keyslot, dek: ByteArray, slotIndex: Int): Boolean {
        val markerKey = crypto.hkdfSha256(
            ikm = dek,
            salt = "slot-marker-v1".encodeToByteArray(),
            info = byteArrayOf(slotIndex.toByte()),
            length = 4,
        )
        val matches = slot.reserved[0] == markerKey[0] && slot.reserved[1] == markerKey[1] &&
                      slot.reserved[2] == markerKey[2] && slot.reserved[3] == markerKey[3]
        crypto.clearBytes(markerKey)
        return matches
    }

    private fun namespaceFirstSlot(namespace: VaultNamespace) = when (namespace) {
        VaultNamespace.OUTER -> 0
        VaultNamespace.HIDDEN -> 4
    }

    private fun namespaceSlotRange(namespace: VaultNamespace) = when (namespace) {
        VaultNamespace.OUTER -> 0..3
        VaultNamespace.HIDDEN -> 4..7
    }

    private fun deriveHeaderMacKey(dek: ByteArray): ByteArray =
        crypto.hkdfSha256(
            ikm = dek,
            salt = "vault-header-mac".encodeToByteArray(),
            info = "v1".encodeToByteArray(),
            length = 32,
        )

    private fun computeHeaderMac(macKey: ByteArray, data: ByteArray): ByteArray =
        crypto.hmacSha256(macKey, data)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        crypto.constantTimeEquals(a, b)

    companion object {
        fun vaultFilePath(graphPath: String): String {
            val base = if (graphPath.endsWith("/")) graphPath.dropLast(1) else graphPath
            return "$base/.stele-vault"
        }

        fun hiddenReserveSentinelPath(graphPath: String): String {
            val base = if (graphPath.endsWith("/")) graphPath.dropLast(1) else graphPath
            return "$base/_hidden_reserve/.stele-reserve"
        }
    }
}

/**
 * Encodes a CharArray to UTF-8 bytes without creating a String intermediate.
 * Avoids heap-interning of the passphrase in a JVM String that cannot be zeroed.
 * Handles BMP code points (U+0000–U+FFFF); surrogate pairs are encoded as three bytes each,
 * which differs from standard UTF-8 but is deterministic and acceptable for passphrase hashing.
 */
private fun CharArray.toByteArray(): ByteArray {
    // First pass: count output bytes to avoid boxing via ArrayList<Byte>
    var byteCount = 0
    for (c in this) {
        byteCount += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
    }
    val out = ByteArray(byteCount)
    var i = 0
    for (c in this) {
        val code = c.code
        when {
            code < 0x80 -> { out[i++] = code.toByte() }
            code < 0x800 -> {
                out[i++] = (0xC0 or (code shr 6)).toByte()
                out[i++] = (0x80 or (code and 0x3F)).toByte()
            }
            else -> {
                out[i++] = (0xE0 or (code shr 12)).toByte()
                out[i++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                out[i++] = (0x80 or (code and 0x3F)).toByte()
            }
        }
    }
    return out
}
