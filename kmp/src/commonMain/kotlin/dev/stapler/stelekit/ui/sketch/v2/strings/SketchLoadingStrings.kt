// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** Loading states (Story 3.6.8, Task 3.6.8c, UX-94): the operation names, the Cancel label and the placeholder text. */
object SketchLoadingStrings {
    const val CANCEL = "Cancel"
    const val EXPORT = "Exporting"
    const val LIBRARY = "Opening library"
    const val IMPORT = "Importing"
    const val THUMBNAIL = "Loading sketch preview"
    const val DONE_SUFFIX = "finished"

    /** Spoken once when a long operation's indicator appears: "Exporting...". */
    fun started(name: String): String = "$name..."

    /** Spoken once when it ends: "Exporting finished". */
    fun finished(name: String): String = "$name $DONE_SUFFIX"

    /** The visible line beside a determinate bar: "Exporting 40%". */
    fun percent(name: String, percent: Int): String = "$name $percent%"

    /** The accessible name of a Cancel button that stops [name]. */
    fun cancelName(name: String): String = "Cancel: $name"
}
