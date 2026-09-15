package dev.stapler.stelekit.db

import kotlin.Long

public data class SelectHistogramForOperation(
  public val bucket_ms: Long,
  public val count: Long,
)
