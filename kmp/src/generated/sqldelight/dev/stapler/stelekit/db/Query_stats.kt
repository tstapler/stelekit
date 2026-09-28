package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Query_stats(
  public val app_version: String,
  public val table_name: String,
  public val operation: String,
  public val calls: Long,
  public val errors: Long,
  public val total_ms: Long,
  public val min_ms: Long,
  public val max_ms: Long,
  public val b1: Long,
  public val b5: Long,
  public val b16: Long,
  public val b50: Long,
  public val b100: Long,
  public val b500: Long,
  public val b_inf: Long,
  public val first_seen: Long,
  public val last_seen: Long,
)
