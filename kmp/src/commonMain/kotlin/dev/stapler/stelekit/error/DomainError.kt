package dev.stapler.stelekit.error

sealed interface DomainError {
    val message: String

    sealed interface DatabaseError : DomainError {
        data class WriteFailed(override val message: String) : DatabaseError
        data class ReadFailed(override val message: String) : DatabaseError
        data class NotFound(val entity: String, val id: String) : DatabaseError {
            override val message: String = "$entity not found: $id"
        }
        data class TransactionFailed(override val message: String) : DatabaseError
    }

    sealed interface FileSystemError : DomainError {
        data class NotFound(val path: String) : FileSystemError {
            override val message: String = "File not found: $path"
        }
        data class WriteFailed(val path: String, override val message: String) : FileSystemError
        data class ReadFailed(val path: String, override val message: String) : FileSystemError
        data class DeleteFailed(val path: String, override val message: String) : FileSystemError
    }

    sealed interface ParseError : DomainError {
        data class EmptyFile(val path: String) : ParseError {
            override val message: String = "Empty file: $path"
        }
        data class InvalidSyntax(override val message: String) : ParseError
        data class MalformedMarkdown(override val message: String) : ParseError
    }

    sealed interface ConflictError : DomainError {
        data class DiskConflict(val pageUuid: String, override val message: String) : ConflictError
        data class ConcurrentWrite(val pageUuid: String, override val message: String) : ConflictError
    }

    sealed interface ValidationError : DomainError {
        data class InvalidUuid(val uuid: String) : ValidationError {
            override val message: String = "Invalid UUID: $uuid"
        }
        data class EmptyName(override val message: String) : ValidationError
        data class ConstraintViolation(override val message: String) : ValidationError
    }

    sealed interface NetworkError : DomainError {
        data class HttpError(val statusCode: Int, override val message: String) : NetworkError
        data class CircuitOpen(override val message: String = "Circuit breaker is open") : NetworkError
        data class Timeout(override val message: String) : NetworkError
        data class RequestFailed(override val message: String) : NetworkError
    }

    sealed interface SensorError : DomainError {
        data class PermissionDenied(val sensor: String) : SensorError {
            override val message: String = "Permission denied for sensor: $sensor"
        }
        data class HardwareUnavailable(val sensor: String) : SensorError {
            override val message: String = "Hardware unavailable: $sensor"
        }
        data class CaptureFailed(override val message: String) : SensorError
    }

    sealed interface BleError : DomainError {
        data class ConnectionFailed(override val message: String) : BleError
        data class Gatt133(val attempts: Int, override val message: String) : BleError {
            override fun toString(): String = "GATT 133 after $attempts attempts: $message"
        }
    }

    sealed interface GitError : DomainError {
        data class CloneFailed(override val message: String) : GitError
        data class FetchFailed(override val message: String) : GitError
        data class PushFailed(override val message: String) : GitError
        data class AuthFailed(override val message: String) : GitError
        data class MergeConflict(val conflictCount: Int, val conflictPaths: List<String> = emptyList()) : GitError {
            override val message: String = "Merge conflict in $conflictCount file(s)"
        }
        data class CommitFailed(override val message: String) : GitError
        data class NotAGitRepo(val path: String) : GitError {
            override val message: String = "Not a git repository: $path"
        }
        data class DetachedHead(val path: String) : GitError {
            override val message: String = "Repository is in detached HEAD state: $path"
        }
        data class StaleLockFile(val lockPath: String) : GitError {
            override val message: String = "Stale git lock file found: $lockPath"
        }
        data class NotSupported(val platform: String) : GitError {
            override val message: String = "Git integration not yet supported on $platform"
        }
        data object Offline : GitError {
            override val message: String = "No network connection available"
        }
        data object EditingInProgress : GitError {
            override val message: String = "Cannot sync while editing is in progress"
        }
        data class CredentialExpired(override val message: String) : GitError
    }

    sealed interface AttachmentError : DomainError {
        data class CopyFailed(override val message: String) : AttachmentError
        data class PickerFailed(override val message: String) : AttachmentError
        data class AssetsDirectoryFailed(override val message: String) : AttachmentError
    }

    sealed interface ExportError : DomainError {
        data class SerializationFailed(override val message: String) : ExportError
        data class ClipboardFailed(override val message: String) : ExportError
        data class ShareFailed(override val message: String) : ExportError
    }

    /**
     * Five variants, not the original six (Story 1.1.2): `IncompleteTransfer(received, total)` and
     * `TransferCancelled` were removed as dead code after an audit found no principled call site —
     * see `ChunkBuffer.reassemble` and `QrTransferCoordinator.cancel` KDoc for why.
     * `EnvelopeMalformed` was added afterward (see `TransferPayloadEnvelope`) for the one failure
     * mode that can only occur AFTER `IntegrityCheckFailed`'s CRC32 gate already passed: the
     * reassembled bytes are provably intact but don't parse as a valid name+markdown envelope.
     */
    sealed interface QrTransferError : DomainError {
        data object ChunkDecodeFailed : QrTransferError {
            override val message: String = "Failed to decode QR chunk"
        }
        data object IntegrityCheckFailed : QrTransferError {
            override val message: String = "Transfer integrity check failed"
        }
        data class PayloadTooLarge(val sizeBytes: Int, val maxBytes: Int) : QrTransferError {
            override val message: String = "Payload too large: $sizeBytes bytes exceeds max of $maxBytes bytes"
        }
        data object MarkdownParseFailed : QrTransferError {
            override val message: String = "Failed to parse received markdown"
        }
        data object EnvelopeMalformed : QrTransferError {
            override val message: String = "Transfer payload envelope is malformed"
        }

        /**
         * [dev.stapler.stelekit.transfer.qrcode.QrImportService.import]'s overwrite path clears the
         * pre-existing page's blocks before writing the new ones; if the new-block write then
         * fails, the page row is deliberately left in place (never deleted — that would destroy the
         * pre-existing page beyond what this failed operation should be allowed to touch) but its
         * blocks may now be empty or partial. Distinct from [MarkdownParseFailed] so the UI can
         * tell the user their previous content on this page may have been affected, not just that
         * the new import failed.
         */
        data class OverwriteFailedPreviousContentAffected(val pageUuid: String) : QrTransferError {
            override val message: String =
                "Overwrite failed after clearing previous content for page $pageUuid — previous content may be lost"
        }
    }
}

fun Throwable.toDatabaseError(): DomainError.DatabaseError.WriteFailed =
    DomainError.DatabaseError.WriteFailed(message ?: "unknown")

fun DomainError.toUiMessage(): String = when (this) {
    is DomainError.DatabaseError.WriteFailed -> "Save failed"
    is DomainError.DatabaseError.ReadFailed -> "Read failed"
    is DomainError.DatabaseError.NotFound -> "Not found"
    is DomainError.DatabaseError.TransactionFailed -> "Transaction failed"
    is DomainError.FileSystemError.NotFound -> "File not found"
    is DomainError.FileSystemError.WriteFailed -> "File write failed"
    is DomainError.FileSystemError.ReadFailed -> "File read failed"
    is DomainError.FileSystemError.DeleteFailed -> "File delete failed"
    is DomainError.ParseError.EmptyFile -> "File is empty"
    is DomainError.ParseError.InvalidSyntax -> "Parse error"
    is DomainError.ParseError.MalformedMarkdown -> "Malformed markdown"
    is DomainError.ConflictError.DiskConflict -> "Disk conflict detected"
    is DomainError.ConflictError.ConcurrentWrite -> "Concurrent write conflict"
    is DomainError.ValidationError.InvalidUuid -> "Invalid identifier"
    is DomainError.ValidationError.EmptyName -> "Name cannot be empty"
    is DomainError.ValidationError.ConstraintViolation -> "Validation failed"
    is DomainError.NetworkError.HttpError -> "Network error (HTTP $statusCode)"
    is DomainError.NetworkError.CircuitOpen -> "Service temporarily unavailable"
    is DomainError.NetworkError.Timeout -> "Request timed out"
    is DomainError.NetworkError.RequestFailed -> "Request failed"
    is DomainError.SensorError.PermissionDenied -> "Camera permission denied"
    is DomainError.SensorError.HardwareUnavailable -> "Camera unavailable"
    is DomainError.SensorError.CaptureFailed -> "Capture failed"
    is DomainError.BleError.ConnectionFailed -> "BLE connection failed"
    is DomainError.BleError.Gatt133 -> "BLE GATT error after $attempts attempts"
    is DomainError.GitError.CloneFailed -> "Git clone failed"
    is DomainError.GitError.FetchFailed -> "Git fetch failed"
    is DomainError.GitError.PushFailed -> "Git push failed"
    is DomainError.GitError.AuthFailed -> "Git authentication failed — check your credentials"
    is DomainError.GitError.MergeConflict -> message
    is DomainError.GitError.CommitFailed -> "Git commit failed"
    is DomainError.GitError.NotAGitRepo -> "Not a git repository"
    is DomainError.GitError.DetachedHead -> "Repository in detached HEAD state"
    is DomainError.GitError.StaleLockFile -> "Git lock file found — another process may be using the repository"
    is DomainError.GitError.NotSupported -> message
    is DomainError.GitError.Offline -> message
    is DomainError.GitError.EditingInProgress -> message
    is DomainError.GitError.CredentialExpired -> "GitHub authentication expired — tap to re-connect"
    is DomainError.AttachmentError.CopyFailed -> "Attachment failed"
    is DomainError.AttachmentError.PickerFailed -> "Could not open file picker"
    is DomainError.AttachmentError.AssetsDirectoryFailed -> "Cannot create assets directory"
    is DomainError.ExportError.SerializationFailed -> "Export failed"
    is DomainError.ExportError.ClipboardFailed -> "Clipboard write failed"
    is DomainError.ExportError.ShareFailed -> "Share failed"
    is DomainError.QrTransferError.ChunkDecodeFailed -> "Couldn't read that QR code — try again"
    is DomainError.QrTransferError.IntegrityCheckFailed -> "This transfer looks corrupted — please try scanning again"
    is DomainError.QrTransferError.PayloadTooLarge -> "This page is too large to send via QR"
    is DomainError.QrTransferError.MarkdownParseFailed -> "Received data isn't valid page content"
    is DomainError.QrTransferError.EnvelopeMalformed -> "This transfer didn't include valid page info — please try sending it again"
    is DomainError.QrTransferError.OverwriteFailedPreviousContentAffected ->
        "Overwrite failed — this page's previous content may have been affected. Please check it and try again"
}

fun DomainError.GitError.toSyncErrorMessage(): String = when (this) {
    is DomainError.GitError.AuthFailed -> "Authentication failed — tap to update credentials"
    is DomainError.GitError.Offline -> "Offline — sync will resume when connected"
    is DomainError.GitError.DetachedHead -> "Repository is in detached HEAD state"
    is DomainError.GitError.StaleLockFile -> "Stale git lock file — tap to retry"
    is DomainError.GitError.MergeConflict -> "Merge conflict in $conflictCount file(s) — resolve to continue"
    is DomainError.GitError.FetchFailed -> "Fetch failed — tap to retry"
    is DomainError.GitError.PushFailed -> "Push failed — tap to retry"
    is DomainError.GitError.CommitFailed -> "Commit failed — tap to retry"
    is DomainError.GitError.CloneFailed -> "Clone failed — check the repository URL and credentials"
    is DomainError.GitError.NotAGitRepo -> "Not a git repository"
    is DomainError.GitError.NotSupported -> "Git not supported on this platform"
    is DomainError.GitError.EditingInProgress -> "Editing in progress — sync will resume when idle"
    is DomainError.GitError.CredentialExpired -> "GitHub authentication expired — tap to re-connect"
}
