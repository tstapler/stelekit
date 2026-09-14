package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Storage_locations(
  public val graph_id: String,
  public val kind: String,
  public val tree_uri: String?,
  public val real_path: String?,
  public val display_name: String?,
  public val updated_at_epoch_ms: Long,
)
