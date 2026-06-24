package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Perf_histogram_buckets(
  public val id: Long,
  public val operation_name: String,
  public val bucket_ms: Long,
  public val count: Long,
  public val recorded_at: Long,
)
