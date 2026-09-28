package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Properties(
  public val uuid: String,
  public val block_uuid: String,
  public val key: String,
  public val value_: String,
  public val created_at: Long,
)
