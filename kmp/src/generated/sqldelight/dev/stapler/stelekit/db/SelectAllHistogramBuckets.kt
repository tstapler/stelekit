package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectAllHistogramBuckets(
  public val operation_name: String,
  public val bucket_ms: Long,
  public val count: Long,
)
