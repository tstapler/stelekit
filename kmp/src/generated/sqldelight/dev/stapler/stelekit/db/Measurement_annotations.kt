package dev.stapler.stelekit.db

import kotlin.Double
import kotlin.String

public data class Measurement_annotations(
  public val uuid: String,
  public val image_uuid: String,
  public val annotation_type: String,
  public val normalized_points: String,
  public val value_meters: Double?,
  public val value_display: String?,
  public val label: String?,
  public val color_hex: String,
  public val ble_device_id: String?,
)
