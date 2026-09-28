package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectBacklinkCountsForPages(
  public val page_name: String,
  public val backlink_count: Long,
)
