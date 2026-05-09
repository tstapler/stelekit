package dev.stapler.stelekit.vault

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlin.concurrent.Volatile
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
 *
 * **Atomicity requirement**: [fileWriteBytes] MUST write atomically (e.g. write to a
 * `.tmp` sibling file and rename it over the target). A non-atomic implementation risks
 * partial writes on crash/power loss that permanently corrupt the vault header.
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

    /**
     * Holds the DEK and namespace as a single atomic unit.
     * A single @Volatile reference write is atomic on JVM, eliminating the torn-read window
     * that existed when sessionDek and sessionNamespace were two separate @Volatile fields —
     * a reader could previously observe sessionDek != null while sessionNamespace was still null.
     */
    private data class Session(val dek: ByteArray, val namespace: VaultNamespace)

    @Volatile private var session: Session? = null

    sealed interface VaultEvent {
        data object Locked : VaultEvent
        data class Unlocked(val namespace: VaultNamespace) : VaultEvent
    }

    /**
     * The [dek] array is the **same object** that [VaultManager] stores in `session.dek`.
     * When [lock] is called, `session` is set to null and the DEK is zeroed in-place —
     * this also zeroes any [CryptoLayer] built from it. Callers MUST NOT store or copy the
     * DEK array; pass it directly to [CryptoLayer] and let [VaultEvent.Locked] trigger cleanup.
     */
    data class UnlockResult(val dek: ByteArray, val namespace: VaultNamespace)

    /**
     * Create a new vault at [graphPath]/.stele-vault with a single passphrase keyslot.
     *
     * The vault is automatically unlocked after creation — [lock] manages DEK zeroing.
     * Returns an [UnlockResult] so the caller can inject the DEK into a [CryptoLayer]
     * without holding an orphaned ByteArray on the heap.
     *
     * [argon2Params] defaults to [DEFAULT_ARGON2_PARAMS]; supply a calibrated set for production use.
     */
    suspend fun createVault(
        graphPath: String,
        passphrase: CharArray,
        namespace: VaultNamespace = VaultNamespace.OUTER,
        argon2Params: Argon2Params = DEFAULT_ARGON2_PARAMS,
    ): Either<VaultError, UnlockResult> = withContext(Dispatchers.Default) {
        try {
            val dek = crypto.secureRandom(32)
            val slotIndex = namespaceFirstSlot(namespace)
            val keyslot = buildKeyslot(passphrase, dek, namespace, argon2Params, slotIndex)

            val allSlots = (0 until VaultHeader.KEYSLOT_COUNT).map { i ->
                if (i == slotIndex) keyslot else randomSlot()
            }

            val padding = crypto.secureRandom(VaultHeader.PADDING_SIZE)
            val reserved = crypto.secureRandom(VaultHeader.RESERVED_SIZE)

            val header = VaultHeader(
                randomPadding = padding,
                keyslots = allSlots,
                reserved = reserved,
                hiddenHeaderMac = ByteArray(VaultHeader.MAC_SIZE),
                headerMac = ByteArray(VaultHeader.MAC_SIZE),
            )
            val partialBytes = VaultHeaderSerializer.serialize(header)
            val macKey = deriveHeaderMacKey(dek)
            val mac = when (namespace) {
                VaultNamespace.OUTER -> computeHeaderMac(macKey, outerMacAuthData(partialBytes))
                VaultNamespace.HIDDEN -> computeHeaderMac(macKey, hiddenMacAuthData(partialBytes))
            }
            crypto.clearBytes(macKey)
            val finalHeader = when (namespace) {
                VaultNamespace.OUTER -> header.copy(
                    headerMac = mac,
                    hiddenHeaderMac = crypto.secureRandom(VaultHeader.MAC_SIZE),  // random, not zeros
                )
                VaultNamespace.HIDDEN -> header.copy(
                    hiddenHeaderMac = mac,
                    headerMac = crypto.secureRandom(VaultHeader.MAC_SIZE),  // random, not zeros
                )
            }
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

            // Store in session so lock() manages zeroing — symmetric with unlock().
            session = Session(dek, namespace)
            _vaultEvents.tryEmit(VaultEvent.Unlocked(namespace))
            UnlockResult(dek = dek, namespace = namespace).right()
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
        val rawBytes = withContext(PlatformDispatcher.IO) { fileReadBytes(vaultPath) }
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
                // Validate params before deriving — extreme values (memory = Int.MAX_VALUE) in
                // a crafted vault file would cause OOM before the header MAC rejects it.
                // Use `continue` rather than aborting: decoy slots have random bytes and can
                // legitimately produce zero or extreme params (~1/2^32 per slot per field).
                if (params.memory < 1 || params.iterations < 1 || params.parallelism < 1
                    || params.memory > MAX_ARGON2_MEMORY_KIB) {
                    continue
                }
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
                    // First-match wins: validDek == null guard discards subsequent active slots.
                    // All 8 slots are still tried unconditionally for constant-time deniability.
                    if (plaintext.size == 34 && validDek == null) {
                        val dek = plaintext.sliceArray(0 until 32)
                        val ns = try {
                            VaultNamespace.fromTag(plaintext[32])
                        } catch (_: VaultAuthException) {
                            crypto.clearBytes(dek)
                            crypto.clearBytes(plaintext)
                            continue
                        }
                        // Verify namespace-specific header MAC using the recovered DEK
                        val macKey = deriveHeaderMacKey(dek)
                        val authData = when (ns) {
                            VaultNamespace.OUTER -> outerMacAuthData(rawBytes)
                            VaultNamespace.HIDDEN -> hiddenMacAuthData(rawBytes)
                        }
                        val expectedMac = computeHeaderMac(macKey, authData)
                        crypto.clearBytes(macKey)
                        val storedMac = when (ns) {
                            VaultNamespace.OUTER -> header.headerMac
                            VaultNamespace.HIDDEN -> header.hiddenHeaderMac
                        }
                        if (constantTimeEquals(expectedMac, storedMac)) {
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
                } finally {
                    // Always zero keyslotKey — `continue` in the inner catch would otherwise
                    // skip the clearBytes call, leaving the Argon2id-derived key in memory.
                    crypto.clearBytes(keyslotKey)
                }
            }

            if (validDek == null || validNamespace == null) {
                if (validDek != null) crypto.clearBytes(validDek)
                return@withContext if (macFailed) {
                    VaultError.HeaderTampered().left()
                } else {
                    VaultError.InvalidCredential().left()
                }
            }

            val newSession = Session(validDek, validNamespace)
            session = newSession  // single atomic reference write — no torn-read possible
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
     *
     * **Mandatory call ordering**: callers MUST clear all [CryptoLayer] references
     * (`graphWriter.cryptoLayer = null`, `graphLoader.cryptoLayer = null`) and flush
     * pending saves (`graphWriter.flush()`) BEFORE calling this method. If `lock()` zeroes
     * the DEK while an in-flight `encrypt()` call is still using it, the file is silently
     * written with an all-zero key and becomes permanently corrupted.
     *
     * The [VaultEvent.Locked] handler in App.kt also flushes and clears references, but
     * that fires AFTER the DEK is already zeroed — it is a safety net for unexpected lock
     * events only, not the primary lock path.
     */
    fun lock() {
        val s = session
        session = null  // single atomic reference write — visible immediately to other threads (@Volatile)
        s?.let { crypto.clearBytes(it.dek) }
        _vaultEvents.tryEmit(VaultEvent.Locked)
    }

    /** Returns the current in-memory DEK (null when locked). */
    fun currentDek(): ByteArray? = session?.dek

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
        val localDek = dek.copyOf()   // isolated from concurrent lock() zeroing of the live session array
        try {
            val vaultPath = vaultFilePath(graphPath)
            val rawBytes = withContext(PlatformDispatcher.IO) { fileReadBytes(vaultPath) }
                ?: return@withContext VaultError.NotAVault("Vault file not found").left()
            val header = when (val r = VaultHeaderSerializer.deserialize(rawBytes)) {
                is Either.Left -> return@withContext r
                is Either.Right -> r.value
            }

            // Verify the provided DEK is correct before mutating any slots.
            // Without this check, a caller with a wrong DEK could overwrite active slots
            // because the marker comparison would fail for all slots (false negatives).
            // Use namespace-specific MAC so OUTER DEK cannot be verified against HIDDEN MAC.
            val verifyMacKey = deriveHeaderMacKey(localDek)
            val authData = when (namespace) {
                VaultNamespace.OUTER -> outerMacAuthData(rawBytes)
                VaultNamespace.HIDDEN -> hiddenMacAuthData(rawBytes)
            }
            val actualMac = computeHeaderMac(verifyMacKey, authData)
            crypto.clearBytes(verifyMacKey)
            val expectedMac = when (namespace) {
                VaultNamespace.OUTER -> header.headerMac
                VaultNamespace.HIDDEN -> header.hiddenHeaderMac
            }
            val dekValid = constantTimeEquals(actualMac, expectedMac)
            crypto.clearBytes(actualMac)
            if (!dekValid) {
                return@withContext VaultError.InvalidCredential("Provided DEK does not match vault header").left()
            }

            // Namespace guard: an active session may only add slots within its own namespace.
            // Allowing an OUTER session to embed the OUTER DEK in a HIDDEN slot would let
            // anyone with the outer passphrase automatically recover the "hidden" DEK.
            val currentNs = session?.namespace
            if (currentNs != null && namespace != currentNs) {
                return@withContext VaultError.InvalidCredential(
                    "Active session namespace ($currentNs) cannot add keyslots to $namespace"
                ).left()
            }

            val targetSlots = namespaceSlotRange(namespace)
            // A slot is "mine" if its reserved[0..3] matches the 4-byte DEK-derived marker.
            // Slots without the marker (decoy slots) are safe to overwrite.
            val emptySlotIndex = targetSlots.firstOrNull { index ->
                !isSlotMine(header.keyslots[index], localDek, index)
            } ?: return@withContext VaultError.SlotsFull().left()

            val newSlot = buildKeyslot(passphrase, localDek, namespace, argon2Params, emptySlotIndex)
            val updatedSlots = header.keyslots.toMutableList()
            updatedSlots[emptySlotIndex] = newSlot

            writeUpdatedHeader(vaultPath, localDek, namespace, header.copy(keyslots = updatedSlots))
        } finally {
            passphrase.fill(' ')
            crypto.clearBytes(localDek)
            // Do NOT zero the original `dek` param — it's the live session array owned by lock()
        }
    }

    /**
     * Overwrite keyslot at [slotIndex] with random bytes (effectively removing it).
     * The DEK is not re-encrypted — remaining providers can still unlock.
     *
     * Enforces two guards before mutating the header:
     * 1. [slotIndex] must be a valid keyslot index (0 until KEYSLOT_COUNT).
     * 2. [slotIndex] must belong to the currently authenticated namespace — an OUTER session
     *    cannot remove HIDDEN keyslots (slots 4–7) and vice versa.
     */
    suspend fun removeKeyslot(
        graphPath: String,
        slotIndex: Int,
    ): Either<VaultError, Unit> = withContext(Dispatchers.Default) {
        val currentSession = session ?: return@withContext VaultError.InvalidCredential("Vault is locked").left()
        val dek = currentSession.dek.copyOf()   // isolated from concurrent lock() zeroing
        val ns = currentSession.namespace
        try {
            if (slotIndex !in 0 until VaultHeader.KEYSLOT_COUNT) {
                return@withContext VaultError.InvalidCredential("Slot index $slotIndex is out of range").left()
            }
            if (slotIndex !in namespaceSlotRange(ns)) {
                return@withContext VaultError.InvalidCredential(
                    "Slot $slotIndex does not belong to the current namespace"
                ).left()
            }

            val vaultPath = vaultFilePath(graphPath)
            val rawBytes = withContext(PlatformDispatcher.IO) { fileReadBytes(vaultPath) }
                ?: return@withContext VaultError.NotAVault("Vault file not found").left()
            val header = when (val r = VaultHeaderSerializer.deserialize(rawBytes)) {
                is Either.Left -> return@withContext r
                is Either.Right -> r.value
            }

            // Refuse to remove the last active slot — it would permanently lock out the vault.
            val activeCount = namespaceSlotRange(ns).count { i -> isSlotMine(header.keyslots[i], dek, i) }
            if (activeCount <= 1) {
                return@withContext VaultError.InvalidCredential(
                    "Cannot remove the last keyslot in namespace $ns — vault would be permanently locked"
                ).left()
            }

            val updatedSlots = header.keyslots.toMutableList()
            updatedSlots[slotIndex] = randomSlot()
            writeUpdatedHeader(vaultPath, dek, ns, header.copy(keyslots = updatedSlots))
        } finally {
            crypto.clearBytes(dek)
        }
    }

    private fun writeUpdatedHeader(
        vaultPath: String,
        dek: ByteArray,
        namespace: VaultNamespace,
        header: VaultHeader,
    ): Either<VaultError, Unit> {
        val partialBytes = VaultHeaderSerializer.serialize(header.copy(
            hiddenHeaderMac = ByteArray(VaultHeader.MAC_SIZE),
            headerMac = ByteArray(VaultHeader.MAC_SIZE)
        ))
        val macKey = deriveHeaderMacKey(dek)
        val mac = when (namespace) {
            VaultNamespace.OUTER -> computeHeaderMac(macKey, outerMacAuthData(partialBytes))
            VaultNamespace.HIDDEN -> computeHeaderMac(macKey, hiddenMacAuthData(partialBytes))
        }
        crypto.clearBytes(macKey)
        val finalHeader = when (namespace) {
            VaultNamespace.OUTER -> header.copy(
                headerMac = mac,
                hiddenHeaderMac = crypto.secureRandom(VaultHeader.MAC_SIZE),
            )
            VaultNamespace.HIDDEN -> header.copy(
                hiddenHeaderMac = mac,
                headerMac = crypto.secureRandom(VaultHeader.MAC_SIZE),
            )
        }
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
        val matches = constantTimeEquals(slot.reserved.sliceArray(0 until 4), markerKey)
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

    /** Returns the authenticated region for the OUTER namespace MAC: bytes[0..1036] (contiguous). */
    private fun outerMacAuthData(rawBytes: ByteArray): ByteArray =
        rawBytes.sliceArray(0 until VaultHeader.OUTER_MAC_AUTH_SIZE)

    /**
     * Returns the authenticated region for the HIDDEN namespace MAC:
     * bytes[0..12] (magic+version+padding) ++ bytes[1037..2060] (slots 4-7).
     * Non-contiguous — neither range overlaps the OUTER keyslot region.
     */
    private fun hiddenMacAuthData(rawBytes: ByteArray): ByteArray =
        rawBytes.sliceArray(0 until VaultHeader.HEADER_PREFIX_SIZE) +
        rawBytes.sliceArray(VaultHeader.HIDDEN_SLOT_START until VaultHeader.HIDDEN_SLOT_END)

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
        /** Maximum Argon2id memory (KiB) accepted from stored vault params — 4 GiB. */
        const val MAX_ARGON2_MEMORY_KIB = 4 * 1024 * 1024  // 4 GiB in KiB

        fun vaultFilePath(graphPath: String): String {
            require(graphPath.isNotEmpty()) { "graphPath must not be empty" }
            require(!graphPath.contains("..")) { "graphPath must not contain '..' path traversal" }
            val base = if (graphPath.endsWith("/")) graphPath.dropLast(1) else graphPath
            return "$base/.stele-vault"
        }

        fun hiddenReserveSentinelPath(graphPath: String): String {
            require(graphPath.isNotEmpty()) { "graphPath must not be empty" }
            require(!graphPath.contains("..")) { "graphPath must not contain '..' path traversal" }
            val base = if (graphPath.endsWith("/")) graphPath.dropLast(1) else graphPath
            return "$base/_hidden_reserve/.stele-reserve"
        }
    }
}

/**
 * Encodes a CharArray to UTF-8 bytes without creating a String intermediate.
 * Avoids heap-interning of the passphrase in a JVM String that cannot be zeroed.
 * Handles BMP code points (U+0000–U+FFFF); surrogate pairs are encoded as three bytes each
 * (CESU-8 style), which differs from standard UTF-8's four-byte encoding for U+10000+.
 *
 * **Compatibility warning**: passphrases containing emoji or other supplementary characters
 * (U+10000 and above) will produce different bytes than a standard UTF-8 encoder. Any future
 * re-implementation MUST replicate this encoding or such vaults become irrecoverable.
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
