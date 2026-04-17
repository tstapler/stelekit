package dev.stapler.stelekit.repository

/**
 * Marks a repository write method that must only be called through [dev.stapler.stelekit.db.DatabaseWriteActor].
 * Direct callers will receive a compile-time error. Use [dev.stapler.stelekit.db.DatabaseWriteActor] instead.
 */
@RequiresOptIn(
    message = "Repository writes must go through DatabaseWriteActor to prevent SQLITE_BUSY. Use the actor instead.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class DirectRepositoryWrite
