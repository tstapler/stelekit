package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectAnnotationTagSets(
  public val tags: String,
  public val uses: Long,
)
