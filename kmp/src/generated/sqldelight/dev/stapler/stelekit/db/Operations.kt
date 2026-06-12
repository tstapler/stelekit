package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Operations(
  public val op_id: String,
  public val session_id: String,
  public val seq: Long,
  public val op_type: String,
  public val entity_uuid: String?,
  public val page_uuid: String?,
  public val payload: String,
  public val created_at: Long,
)
