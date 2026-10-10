package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class CountBlocksByPageUuids(
  public val page_uuid: String,
  public val block_count: Long,
)
