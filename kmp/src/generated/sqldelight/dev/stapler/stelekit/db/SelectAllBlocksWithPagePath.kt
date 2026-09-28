package dev.stapler.stelekit.db

import kotlin.String

public data class SelectAllBlocksWithPagePath(
  public val uuid: String,
  public val parent_uuid: String?,
  public val position: String,
  public val content: String,
  public val file_path: String,
)
