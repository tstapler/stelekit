package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Plugin_data(
  public val id: Long,
  public val plugin_id: String,
  public val entity_type: String,
  public val entity_uuid: String,
  public val key: String,
  public val value_: String,
  public val created_at: Long,
  public val updated_at: Long?,
)
