package dev.stapler.stelekit.platform

/** Boolean setting, default on: a placement tool returns to Select after its measurement commits (ADR-005). */
const val ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY = "annotator_auto_return_to_select"

/** Boolean setting, default off: placement tools stay armed after a commit ("Keep tools active"). */
const val ANNOTATOR_STICKY_TOOLS_KEY = "annotator_sticky_tools"

fun Settings?.annotatorAutoReturnToSelect(): Boolean =
    this?.getBoolean(ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY, true) ?: true

fun Settings?.annotatorStickyTools(): Boolean =
    this?.getBoolean(ANNOTATOR_STICKY_TOOLS_KEY, false) ?: false
