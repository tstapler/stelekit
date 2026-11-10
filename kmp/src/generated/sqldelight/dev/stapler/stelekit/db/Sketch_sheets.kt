package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Sketch_sheets(
  public val uuid: String,
  public val project_uuid: String,
  public val kind: String,
  public val view_direction: String,
  public val unit: String,
  public val scale_sixteenths_per_unit: Long,
  public val source_asset_uuid: String?,
  public val layer_json: String,
  public val zones_json: String,
  public val position: Long,
)
