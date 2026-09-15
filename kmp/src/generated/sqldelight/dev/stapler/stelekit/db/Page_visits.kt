package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Page_visits(
  public val page_uuid: String,
  public val visit_count: Long,
  public val last_visited_at: Long,
)
