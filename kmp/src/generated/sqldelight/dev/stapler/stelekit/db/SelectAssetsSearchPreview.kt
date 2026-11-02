package dev.stapler.stelekit.db

import kotlin.String

public data class SelectAssetsSearchPreview(
  public val uuid: String,
  public val matched_column: String,
  public val excerpt: String?,
)
