// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.misplaced

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Points page rows at the file a misplaced page moved to, so the editor saves to the new place. */
class PageFilePathRepointer(
    private val db: SteleDatabase,
    private val writeActor: DatabaseWriteActor,
) {
    /** [moves] maps an old absolute file path to its new one; rows pointing anywhere else are left alone. */
    @OptIn(DirectSqlWrite::class)
    suspend fun repoint(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        val rows = db.steleDatabaseQueries.selectPageUuidAndFilePath()
            .asFlow().mapToList(PlatformDispatcher.DB).first()
            .filter { it.file_path in moves }
        if (rows.isEmpty()) return
        val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)
        writeActor.execute {
            try {
                restricted.transaction { rows.forEach { restricted.updatePageFilePathByUuid(moves.getValue(it.file_path!!), it.uuid) } }
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }
}
