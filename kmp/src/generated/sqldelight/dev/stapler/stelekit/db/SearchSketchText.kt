package dev.stapler.stelekit.db

import kotlin.String

public data class SearchSketchText(
  public val project_uuid: String,
  public val sheet_uuid: String,
  public val body: String,
  public val project_name: String,
)
