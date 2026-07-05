package dev.stapler.stelekit.repository

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single shared definition of the closed-DB guard for all SqlDelight repository flows.
 *
 * Previously copy-pasted into each repository file as a private extension — that pattern
 * meant new files could omit it and new methods could bypass it silently. Centralised here
 * so there is exactly one place to update and no per-file obligation to remember.
 *
 * All SqlDelight `asFlow()` chains MUST end with this operator. The preferred way to
 * enforce that is to use [asDbFlowList] or [asDbFlowOrNull] below, which apply it
 * automatically and make forgetting structurally harder than remembering.
 */
internal fun <T> Flow<Either<DomainError, T>>.catchDbError(): Flow<Either<DomainError, T>> =
    catch { e ->
        if (e is CancellationException) throw e
        emit(DomainError.DatabaseError.ReadFailed(e.message ?: "database closed").left())
    }

/**
 * Replaces the three-step boilerplate:
 * ```
 * query.asFlow().mapToList(dispatcher).map { rows -> rows.map(mapper).right() }.catchDbError()
 * ```
 *
 * Use this instead of calling [Query.asFlow] directly on any query that returns a list.
 * The closed-DB guard ([catchDbError]) is built in and cannot be accidentally omitted.
 */
internal fun <SqlRow : Any, Domain> Query<SqlRow>.asDbFlowList(
    dispatcher: CoroutineDispatcher,
    mapper: (SqlRow) -> Domain,
): Flow<Either<DomainError, List<Domain>>> =
    asFlow()
        .mapToList(dispatcher)
        .map { rows -> rows.map(mapper).right() }
        .catchDbError()

/**
 * Replaces the three-step boilerplate:
 * ```
 * query.asFlow().mapToOneOrNull(dispatcher).map { row -> row?.let(mapper).right() }.catchDbError()
 * ```
 *
 * Use this instead of calling [Query.asFlow] directly on any query that returns one-or-null.
 * The closed-DB guard ([catchDbError]) is built in and cannot be accidentally omitted.
 */
internal fun <SqlRow : Any, Domain> Query<SqlRow>.asDbFlowOrNull(
    dispatcher: CoroutineDispatcher,
    mapper: (SqlRow) -> Domain,
): Flow<Either<DomainError, Domain?>> =
    asFlow()
        .mapToOneOrNull(dispatcher)
        .map { row -> row?.let(mapper).right() }
        .catchDbError()

/**
 * One-shot suspend read returning a list — works on both sync and async drivers (including WASM).
 *
 * `executeAsList()` calls `QueryResult.AsyncValue.value` synchronously, which throws on the
 * WASM OPFS driver. This helper routes through `asFlow().mapToList(dispatcher).first()`, which
 * properly awaits async query results on every platform.
 *
 * Throws [DomainError.DatabaseError.ReadFailed] wrapped in [Either.Left] on failure.
 */
internal suspend fun <SqlRow : Any, Domain> Query<SqlRow>.asDbQueryList(
    dispatcher: CoroutineDispatcher,
    mapper: (SqlRow) -> Domain,
): Either<DomainError, List<Domain>> =
    try {
        asFlow().mapToList(dispatcher).first().map(mapper).right()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        DomainError.DatabaseError.ReadFailed(e.message ?: "database closed").left()
    }

/**
 * One-shot suspend read returning one-or-null — works on both sync and async drivers (including WASM).
 *
 * `executeAsOneOrNull()` calls `QueryResult.AsyncValue.value` synchronously, which throws on the
 * WASM OPFS driver. This helper routes through `asFlow().mapToOneOrNull(dispatcher).first()`.
 *
 * Throws [DomainError.DatabaseError.ReadFailed] wrapped in [Either.Left] on failure.
 */
internal suspend fun <SqlRow : Any, Domain> Query<SqlRow>.asDbQueryOrNull(
    dispatcher: CoroutineDispatcher,
    mapper: (SqlRow) -> Domain,
): Either<DomainError, Domain?> =
    try {
        asFlow().mapToOneOrNull(dispatcher).first()?.let(mapper).right()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        DomainError.DatabaseError.ReadFailed(e.message ?: "database closed").left()
    }
