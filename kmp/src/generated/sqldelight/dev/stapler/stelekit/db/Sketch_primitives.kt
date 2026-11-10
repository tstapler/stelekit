package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Sketch_primitives(
  public val uuid: String,
  public val sheet_uuid: String,
  public val kind: String,
  public val x1: Long,
  public val y1: Long,
  public val x2: Long,
  public val y2: Long,
  public val name: String,
  public val layer: String,
  public val z: Long,
)
