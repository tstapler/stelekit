package dev.stapler.stelekit.ui.components

/** GitLab's precedent for the max source length it will attempt to render as a diagram. */
const val MAX_MERMAID_SOURCE_LENGTH = 2000

/** Watchdog timeout for an in-flight render, independent of Mermaid's own character cap. */
const val MERMAID_RENDER_TIMEOUT_MS = 4000L

/** Hardened Mermaid securityLevel applied at every JS-execution site; never left at Mermaid's "loose" default. */
const val MERMAID_SECURITY_LEVEL = "strict"
