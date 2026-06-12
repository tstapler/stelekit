package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectAllBlocksWithPagePath(
  public val uuid: String,
  public val parent_uuid: String?,
  public val position: Long,
  public val content: String,
  public val file_path: String,
)
