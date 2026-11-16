package dev.stapler.stelekit.ui.annotate

/** Whether a placement tool disarms after a commit (ONE_SHOT) or stays armed (LOCKED, "Keep tool"). */
enum class ToolStickiness { ONE_SHOT, LOCKED }

/**
 * The tool to arm after a measurement commits (ADR-005). Returns SELECT only for a ONE_SHOT
 * tool with auto-return on and no multi-step session still open; SELECT stays SELECT.
 */
fun nextToolAfterCommit(
    tool: AnnotationTool,
    stickiness: ToolStickiness,
    autoReturnEnabled: Boolean,
    multiStepSessionOpen: Boolean = false,
): AnnotationTool =
    if (autoReturnEnabled && stickiness == ToolStickiness.ONE_SHOT && !multiStepSessionOpen) {
        AnnotationTool.SELECT
    } else {
        tool
    }

/**
 * After a failed persist, restore [toolBeforeCommit] only if the tool is still the one the commit
 * chose ([toolAfterCommit]); a tool the user picked meanwhile wins.
 */
fun nextToolAfterRollback(
    toolBeforeCommit: AnnotationTool,
    toolAfterCommit: AnnotationTool,
    currentTool: AnnotationTool,
): AnnotationTool = if (currentTool == toolAfterCommit) toolBeforeCommit else currentTool
