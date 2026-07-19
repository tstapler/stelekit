package dev.stapler.stelekit.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainErrorTest {

    @Test
    fun exhaustive_when_covers_all_variants() {
        val errors: List<DomainError> = listOf(
            DomainError.DatabaseError.WriteFailed("write"),
            DomainError.DatabaseError.ReadFailed("read"),
            DomainError.DatabaseError.NotFound("page", "abc"),
            DomainError.DatabaseError.TransactionFailed("tx"),
            DomainError.FileSystemError.NotFound("/path"),
            DomainError.FileSystemError.WriteFailed("/path", "write"),
            DomainError.FileSystemError.ReadFailed("/path", "read"),
            DomainError.FileSystemError.DeleteFailed("/path", "delete"),
            DomainError.ParseError.EmptyFile("/path"),
            DomainError.ParseError.InvalidSyntax("syntax"),
            DomainError.ParseError.MalformedMarkdown("md"),
            DomainError.ConflictError.DiskConflict("uuid", "conflict"),
            DomainError.ConflictError.ConcurrentWrite("uuid", "concurrent"),
            DomainError.ValidationError.InvalidUuid("bad-uuid"),
            DomainError.ValidationError.EmptyName("empty"),
            DomainError.ValidationError.ConstraintViolation("constraint"),
            DomainError.NetworkError.HttpError(404, "not found"),
            DomainError.NetworkError.CircuitOpen(),
            DomainError.NetworkError.Timeout("timeout"),
            DomainError.NetworkError.RequestFailed("request failed"),
            DomainError.GitError.CloneFailed("clone"),
            DomainError.GitError.FetchFailed("fetch"),
            DomainError.GitError.PushFailed("push"),
            DomainError.GitError.AuthFailed("auth"),
            DomainError.GitError.MergeConflict(1),
            DomainError.GitError.CommitFailed("commit"),
            DomainError.GitError.NotAGitRepo("/path"),
            DomainError.GitError.DetachedHead("/path"),
            DomainError.GitError.StaleLockFile("/path/.git/index.lock"),
            DomainError.GitError.NotSupported("iOS"),
            DomainError.GitError.Offline,
            DomainError.GitError.EditingInProgress,
            DomainError.GitError.CredentialExpired("expired"),
            DomainError.GitError.RateLimited(42),
            DomainError.GitError.FileTooLarge("assets/large.md.stek", 90_000_000, 75_000_000),
            DomainError.GitError.NetworkFailure("Failed to fetch"),
            DomainError.BleError.ConnectionFailed("ble connect"),
            DomainError.BleError.Gatt133(3, "gatt error"),
            DomainError.SensorError.PermissionDenied("camera"),
            DomainError.SensorError.HardwareUnavailable("lidar"),
            DomainError.SensorError.CaptureFailed("capture failed"),
            DomainError.ExportError.SerializationFailed("serialization failed"),
            DomainError.ExportError.ClipboardFailed("clipboard failed"),
            DomainError.ExportError.ShareFailed("share failed"),
            DomainError.QrTransferError.ChunkDecodeFailed,
            DomainError.QrTransferError.IntegrityCheckFailed,
            DomainError.QrTransferError.PayloadTooLarge(90000, 65536),
            DomainError.QrTransferError.MarkdownParseFailed,
            DomainError.QrTransferError.EnvelopeMalformed,
            DomainError.QrTransferError.OverwriteFailedPreviousContentAffected("page-uuid-123"),
        )
        for (err in errors) {
            // exhaustive when — compile error if any branch is missing
            val msg: String = when (err) {
                is DomainError.DatabaseError.WriteFailed -> err.message
                is DomainError.DatabaseError.ReadFailed -> err.message
                is DomainError.DatabaseError.NotFound -> err.message
                is DomainError.DatabaseError.TransactionFailed -> err.message
                is DomainError.FileSystemError.NotFound -> err.message
                is DomainError.FileSystemError.WriteFailed -> err.message
                is DomainError.FileSystemError.ReadFailed -> err.message
                is DomainError.FileSystemError.DeleteFailed -> err.message
                is DomainError.ParseError.EmptyFile -> err.message
                is DomainError.ParseError.InvalidSyntax -> err.message
                is DomainError.ParseError.MalformedMarkdown -> err.message
                is DomainError.ConflictError.DiskConflict -> err.message
                is DomainError.ConflictError.ConcurrentWrite -> err.message
                is DomainError.ValidationError.InvalidUuid -> err.message
                is DomainError.ValidationError.EmptyName -> err.message
                is DomainError.ValidationError.ConstraintViolation -> err.message
                is DomainError.NetworkError.HttpError -> err.message
                is DomainError.NetworkError.CircuitOpen -> err.message
                is DomainError.NetworkError.Timeout -> err.message
                is DomainError.NetworkError.RequestFailed -> err.message
                is DomainError.GitError.CloneFailed -> err.message
                is DomainError.GitError.FetchFailed -> err.message
                is DomainError.GitError.PushFailed -> err.message
                is DomainError.GitError.AuthFailed -> err.message
                is DomainError.GitError.MergeConflict -> err.message
                is DomainError.GitError.CommitFailed -> err.message
                is DomainError.GitError.NotAGitRepo -> err.message
                is DomainError.GitError.DetachedHead -> err.message
                is DomainError.GitError.StaleLockFile -> err.message
                is DomainError.GitError.NotSupported -> err.message
                DomainError.GitError.Offline -> err.message
                DomainError.GitError.EditingInProgress -> err.message
                is DomainError.GitError.CredentialExpired -> err.message
                is DomainError.GitError.RateLimited -> err.message
                is DomainError.GitError.FileTooLarge -> err.message
                is DomainError.GitError.NetworkFailure -> err.message
                is DomainError.AttachmentError.CopyFailed -> err.message
                is DomainError.AttachmentError.PickerFailed -> err.message
                is DomainError.AttachmentError.AssetsDirectoryFailed -> err.message
                is DomainError.SensorError.PermissionDenied -> err.message
                is DomainError.SensorError.HardwareUnavailable -> err.message
                is DomainError.SensorError.CaptureFailed -> err.message
                is DomainError.BleError.ConnectionFailed -> err.message
                is DomainError.BleError.Gatt133 -> err.message
                is DomainError.ExportError.ClipboardFailed -> err.message
                is DomainError.ExportError.SerializationFailed -> err.message
                is DomainError.ExportError.ShareFailed -> err.message
                DomainError.QrTransferError.ChunkDecodeFailed -> err.message
                DomainError.QrTransferError.IntegrityCheckFailed -> err.message
                is DomainError.QrTransferError.PayloadTooLarge -> err.message
                DomainError.QrTransferError.MarkdownParseFailed -> err.message
                DomainError.QrTransferError.EnvelopeMalformed -> err.message
                is DomainError.QrTransferError.OverwriteFailedPreviousContentAffected -> err.message
            }
            assertTrue(msg.isNotEmpty(), "Expected non-empty message for $err")
        }
    }

    @Test
    fun not_found_message_is_correct() {
        val err = DomainError.DatabaseError.NotFound("page", "abc")
        assertEquals("page not found: abc", err.message)
    }

    @Test
    fun throwable_extension_produces_write_failed() {
        val t = RuntimeException("disk full")
        val err = t.toDatabaseError()
        assertEquals("disk full", err.message)
    }

    @Test
    fun to_ui_message_covers_all_variants() {
        val errors: List<DomainError> = listOf(
            DomainError.DatabaseError.WriteFailed("w"),
            DomainError.DatabaseError.ReadFailed("r"),
            DomainError.DatabaseError.NotFound("p", "1"),
            DomainError.DatabaseError.TransactionFailed("tx"),
            DomainError.FileSystemError.NotFound("/f"),
            DomainError.FileSystemError.WriteFailed("/f", "w"),
            DomainError.FileSystemError.ReadFailed("/f", "r"),
            DomainError.FileSystemError.DeleteFailed("/f", "d"),
            DomainError.ParseError.EmptyFile("/f"),
            DomainError.ParseError.InvalidSyntax("s"),
            DomainError.ParseError.MalformedMarkdown("m"),
            DomainError.ConflictError.DiskConflict("u", "c"),
            DomainError.ConflictError.ConcurrentWrite("u", "c"),
            DomainError.ValidationError.InvalidUuid("bad"),
            DomainError.ValidationError.EmptyName("e"),
            DomainError.ValidationError.ConstraintViolation("v"),
            DomainError.NetworkError.HttpError(500, "err"),
            DomainError.NetworkError.CircuitOpen(),
            DomainError.NetworkError.Timeout("t"),
            DomainError.GitError.CloneFailed("clone"),
            DomainError.GitError.FetchFailed("fetch"),
            DomainError.GitError.PushFailed("push"),
            DomainError.GitError.AuthFailed("auth"),
            DomainError.GitError.MergeConflict(2),
            DomainError.GitError.CommitFailed("commit"),
            DomainError.GitError.NotAGitRepo("/path"),
            DomainError.GitError.DetachedHead("/path"),
            DomainError.GitError.StaleLockFile("/path/.git/index.lock"),
            DomainError.GitError.NotSupported("iOS"),
            DomainError.GitError.Offline,
            DomainError.GitError.EditingInProgress,
            DomainError.GitError.CredentialExpired("expired"),
            DomainError.GitError.RateLimited(42),
            DomainError.GitError.FileTooLarge("assets/large.md.stek", 90_000_000, 75_000_000),
            DomainError.GitError.NetworkFailure("Failed to fetch"),
            DomainError.NetworkError.RequestFailed("req failed"),
            DomainError.AttachmentError.CopyFailed("copy"),
            DomainError.AttachmentError.PickerFailed("picker"),
            DomainError.AttachmentError.AssetsDirectoryFailed("assets"),
            DomainError.SensorError.PermissionDenied("camera"),
            DomainError.SensorError.HardwareUnavailable("lidar"),
            DomainError.SensorError.CaptureFailed("capture"),
            DomainError.BleError.ConnectionFailed("ble connect"),
            DomainError.BleError.Gatt133(3, "gatt error"),
            DomainError.ExportError.ClipboardFailed("clipboard failed"),
            DomainError.ExportError.SerializationFailed("serialization failed"),
            DomainError.ExportError.ShareFailed("share failed"),
            DomainError.QrTransferError.ChunkDecodeFailed,
            DomainError.QrTransferError.IntegrityCheckFailed,
            DomainError.QrTransferError.PayloadTooLarge(90000, 65536),
            DomainError.QrTransferError.MarkdownParseFailed,
            DomainError.QrTransferError.EnvelopeMalformed,
        )
        for (err in errors) {
            assertTrue(err.toUiMessage().isNotEmpty(), "Expected non-empty UI message for $err")
        }
    }

    @Test
    fun toUiMessage_should_ReturnUserFacingSizeMessage_When_PayloadTooLarge() {
        val err = DomainError.QrTransferError.PayloadTooLarge(sizeBytes = 90000, maxBytes = 65536)

        val uiMessage = err.toUiMessage()

        assertEquals("This page is too large to send via QR", uiMessage)
        assertTrue(!uiMessage.contains("90000"), "UI message should not dump the raw byte count: $uiMessage")
        assertTrue(!uiMessage.contains("65536"), "UI message should not dump the raw byte count: $uiMessage")
    }

    @Test
    fun toUiMessage_should_ReturnSixDistinctMessages_When_CalledForEveryQrTransferErrorVariant() {
        // Six variants, not the original six: IncompleteTransfer and TransferCancelled were
        // removed as dead code (no principled call site — see ChunkBuffer.reassemble and
        // QrTransferCoordinator.cancel KDoc). EnvelopeMalformed was added afterward for the
        // page-name-envelope fix (see TransferPayloadEnvelope). OverwriteFailedPreviousContentAffected
        // was added for the Gate 2 C1 fix (QrImportService's overwrite-rollback no longer deletes
        // the pre-existing page — see QrImportService.import KDoc).
        val variants: List<DomainError.QrTransferError> = listOf(
            DomainError.QrTransferError.ChunkDecodeFailed,
            DomainError.QrTransferError.IntegrityCheckFailed,
            DomainError.QrTransferError.PayloadTooLarge(90000, 65536),
            DomainError.QrTransferError.MarkdownParseFailed,
            DomainError.QrTransferError.EnvelopeMalformed,
            DomainError.QrTransferError.OverwriteFailedPreviousContentAffected("page-uuid-123"),
        )

        val messages = variants.map { it.toUiMessage() }

        assertEquals(6, variants.size)
        assertEquals(messages.size, messages.toSet().size, "Expected all six QrTransferError variants to have distinct UI copy: $messages")
    }

    @Test
    fun rate_limited_message_never_suggests_manual_retry() {
        val err = DomainError.GitError.RateLimited(retryAfterSeconds = 42)

        val syncMessage = err.toSyncErrorMessage()

        assertTrue(
            syncMessage.contains("rate limit", ignoreCase = true),
            "Expected sync message to mention rate limiting: $syncMessage",
        )
        assertTrue(
            !syncMessage.contains("tap to retry", ignoreCase = true),
            "RateLimited is auto-resolving and must never suggest a manual retry: $syncMessage",
        )
    }

    @Test
    fun file_too_large_message_contains_path_and_both_byte_counts() {
        val err = DomainError.GitError.FileTooLarge(
            path = "assets/large-export.md.stek",
            sizeBytes = 90_000_000,
            maxBytes = 75_000_000,
        )

        assertTrue(err.message.contains("assets/large-export.md.stek"), "Expected message to contain the path: ${err.message}")
        assertTrue(err.message.contains("90000000") || err.message.contains("90_000_000"), "Expected message to contain the actual size: ${err.message}")
        assertTrue(err.message.contains("75000000") || err.message.contains("75_000_000"), "Expected message to contain the max size: ${err.message}")
    }
}
