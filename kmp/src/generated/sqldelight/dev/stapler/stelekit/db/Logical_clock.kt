package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Logical_clock(
  public val session_id: String,
  public val seq: Long,
)
