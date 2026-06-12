package dev.stapler.stelekit.db

import kotlin.String

public data class SelectAppliedMigrations(
  public val id: String,
  public val checksum: String,
  public val status: String,
)
