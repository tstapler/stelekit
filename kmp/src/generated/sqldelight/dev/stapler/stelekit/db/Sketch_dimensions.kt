package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Sketch_dimensions(
  public val uuid: String,
  public val project_uuid: String,
  public val key: String,
  public val kind: String,
  public val value_num: Long,
  public val value_den: Long,
  public val precision_num: Long?,
  public val precision_den: Long?,
  public val source_kind: String,
  public val source_ref: String,
  public val tolerance_num: Long,
  public val tolerance_den: Long,
  public val locked: Long,
)
