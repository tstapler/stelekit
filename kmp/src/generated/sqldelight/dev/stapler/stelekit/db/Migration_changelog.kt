package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Migration_changelog(
  public val id: String,
  public val graph_id: String,
  public val description: String,
  public val checksum: String,
  public val applied_at: Long,
  public val execution_ms: Long,
  public val status: String,
  public val applied_by: String,
  public val execution_order: Long,
  public val changes_applied: Long,
  public val error_message: String?,
)
