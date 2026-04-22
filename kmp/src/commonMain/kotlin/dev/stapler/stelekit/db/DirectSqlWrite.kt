package dev.stapler.stelekit.db

/**
 * Marks a function or class as performing direct SQLite writes outside repository interfaces.
 *
 * All mutating methods on [RestrictedDatabaseQueries] carry this annotation. Callers must
 * opt in with @OptIn(DirectSqlWrite::class) at the narrowest possible scope — ideally a single
 * private function, never a whole file or module.
 *
 * Approved opt-in sites:
 *  - [DatabaseWriteActor] lambdas executing inside the actor's serialized channel
 *  - [OperationLogger.log] — owns its own transaction, runs inside the actor
 *  - Migration-time writers ([MigrationRunner], [UuidMigration]) — run before the actor exists
 *
 * Do NOT opt in for writes that could run concurrently with the actor. SQLite allows only
 * one writer at a time; concurrent writes from outside the actor cause SQLITE_BUSY errors.
 */
@RequiresOptIn(
    message = "Direct SQL writes must go through DatabaseWriteActor. " +
              "Opt in only within the actor's execute lambda or in migration-time writers.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class DirectSqlWrite
