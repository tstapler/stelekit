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
    }
}

fun Throwable.toDatabaseError(): DomainError.DatabaseError.WriteFailed =
    DomainError.DatabaseError.WriteFailed(message ?: "unknown")

fun DomainError.toUiMessage(): String = when (this) {
    is DomainError.DatabaseError.WriteFailed -> "Save failed: $message"
    is DomainError.DatabaseError.ReadFailed -> "Read failed: $message"
    is DomainError.DatabaseError.NotFound -> message
    is DomainError.DatabaseError.TransactionFailed -> "Transaction failed: $message"
    is DomainError.FileSystemError.NotFound -> message
    is DomainError.FileSystemError.WriteFailed -> "File write failed: $message"
    is DomainError.FileSystemError.ReadFailed -> "File read failed: $message"
    is DomainError.FileSystemError.DeleteFailed -> "File delete failed: $message"
    is DomainError.ParseError.EmptyFile -> message
    is DomainError.ParseError.InvalidSyntax -> "Parse error: $message"
    is DomainError.ParseError.MalformedMarkdown -> "Malformed markdown: $message"
    is DomainError.ConflictError.DiskConflict -> "Disk conflict: $message"
    is DomainError.ConflictError.ConcurrentWrite -> "Concurrent write conflict: $message"
    is DomainError.ValidationError.InvalidUuid -> message
    is DomainError.ValidationError.EmptyName -> "Invalid name: $message"
    is DomainError.ValidationError.ConstraintViolation -> "Validation error: $message"
    is DomainError.NetworkError.HttpError -> "HTTP $statusCode: $message"
    is DomainError.NetworkError.CircuitOpen -> message
    is DomainError.NetworkError.Timeout -> "Request timed out: $message"
}
