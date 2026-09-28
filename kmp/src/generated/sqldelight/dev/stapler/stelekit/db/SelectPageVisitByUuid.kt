package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectPageVisitByUuid(
  public val page_uuid: String,
  public val last_visited_at: Long,
  public val visit_count: Long,
)
