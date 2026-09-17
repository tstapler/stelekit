package dev.stapler.stelekit.ui.components

/** GitLab's precedent for the max source length it will attempt to render as a diagram. */
const val MAX_MERMAID_SOURCE_LENGTH = 2000

/**
 * Watchdog floor for an in-flight render, independent of Mermaid's own character cap.
 * [dev.stapler.stelekit.ui.components.MermaidEngineActor] widens this adaptively based on
 * recently observed render latency (see its own doc) — this is the minimum, used for the very
 * first (unwarmed) call and whenever recent renders have been fast.
 */
const val MERMAID_RENDER_TIMEOUT_MS = 4000L

/**
 * Hard ceiling the adaptive timeout in [dev.stapler.stelekit.ui.components.MermaidEngineActor]
 * never exceeds, however slow recent renders have been — a genuinely wedged GraalJS `Context`
 * (mermaid-js has documented hang bugs, see ADR-001's wasmJs risk section) must still eventually
 * be abandoned and swapped out, not waited on forever.
 */
const val MERMAID_RENDER_TIMEOUT_CEILING_MS = 20_000L

/** Hardened Mermaid securityLevel applied at every JS-execution site; never left at Mermaid's "loose" default. */
const val MERMAID_SECURITY_LEVEL = "strict"
