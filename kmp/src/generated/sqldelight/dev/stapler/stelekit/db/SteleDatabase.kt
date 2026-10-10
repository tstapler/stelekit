package dev.stapler.stelekit.db

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.stapler.stelekit.db.kmp.newInstance
import dev.stapler.stelekit.db.kmp.schema
import kotlin.Unit

public interface SteleDatabase : SuspendingTransacter {
  public val steleDatabaseQueries: SteleDatabaseQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.AsyncValue<Unit>>
      get() = SteleDatabase::class.schema

    public operator fun invoke(driver: SqlDriver): SteleDatabase = SteleDatabase::class.newInstance(driver)
  }
}
