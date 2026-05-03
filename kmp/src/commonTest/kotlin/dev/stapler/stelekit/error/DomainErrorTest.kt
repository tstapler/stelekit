package dev.stapler.stelekit.error

import kotlin.test.Test
import kotlin.test.assertEquals

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
            }
            assert(msg.isNotEmpty()) { "Expected non-empty message for $err" }
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
        )
        for (err in errors) {
            assert(err.toUiMessage().isNotEmpty()) { "Expected non-empty UI message for $err" }
        }
    }
}
