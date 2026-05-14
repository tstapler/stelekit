package dev.stapler.stelekit.vault

/**
 * Vault-specific errors. Used with Arrow Either<VaultError, T> for all vault operations.
 * Not extending DomainError (sealed interface cross-package constraint); callers that need
 * a DomainError can wrap the message in their own error type at the use-site.
 */
sealed class VaultError(open val message: String) {
    /** Passphrase or key-file did not match any keyslot. */
    class InvalidCredential(message: String = "Invalid passphrase or key") : VaultError(message)

    /** AEAD authentication tag did not verify — ciphertext was modified. */
    class AuthenticationFailed(message: String = "Authentication tag verification failed") : VaultError(message)

    /** Header HMAC-SHA256 did not verify after DEK recovery. */
    class HeaderTampered(message: String = "Vault header MAC verification failed") : VaultError(message)

    /** Magic bytes are not "SKVT" — not a vault file. */
    class NotAVault(message: String = "File is not a SteleKit vault") : VaultError(message)

    /** Format version byte is not recognised. */
    class UnsupportedVersion(val version: Int, message: String = "Unsupported vault version: $version") : VaultError(message)

    /** Magic bytes are not "STEK" — not an encrypted file (migration compatibility). */
    class NotEncrypted(message: String = "File is not STEK-encrypted (plaintext)") : VaultError(message)

    /** STEK file is too short to contain a valid header. */
    class CorruptedFile(message: String = "Encrypted file is truncated or corrupted") : VaultError(message)

    /** All 8 keyslots were tried and none verified — header may be corrupt. */
    class NoValidKeyslot(message: String = "No valid keyslot found in vault header") : VaultError(message)

    /** All keyslots for the requested namespace are occupied. */
    class SlotsFull(message: String = "All keyslots for this namespace are occupied") : VaultError(message)

    /** Attempt to write into the hidden-reserve area from the outer graph. */
    class HiddenAreaWriteDenied(message: String = "Outer graph cannot write to _hidden_reserve/") : VaultError(message)
}
