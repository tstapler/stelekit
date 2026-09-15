package dev.stapler.stelekit.db

import kotlin.Double
import kotlin.Long
import kotlin.String

public data class Image_annotations(
  public val uuid: String,
  public val block_uuid: String,
  public val page_uuid: String,
  public val graph_path: String,
  public val file_path: String,
  public val thumbnail_path: String?,
  public val source: String,
  public val source_uri: String?,
  public val captured_at_ms: Long?,
  public val imported_at_ms: Long,
  public val calibration_method: String,
  public val pixels_per_meter: Double,
  public val calibration_confidence_pct: Long,
  public val unit: String,
  public val tags: String,
  public val lat_lng: String?,
  public val altitude_m: Double?,
  public val bearing_deg: Double?,
  public val pitch_deg: Double?,
  public val roll_deg: Double?,
  public val focal_length_mm: Double?,
  public val focal_length_35mm_eq: Double?,
  public val camera_make: String?,
  public val camera_model: String?,
)
