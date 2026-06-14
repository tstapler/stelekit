package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Pending_asset_moves(
  public val id: Long,
  public val asset_uuid: String,
  public val old_file_path: String,
  public val new_file_path: String,
  public val old_relative_path: String,
  public val new_relative_path: String,
  public val created_at_ms: Long,
)
