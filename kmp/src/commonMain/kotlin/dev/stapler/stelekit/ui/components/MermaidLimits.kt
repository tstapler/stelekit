package dev.stapler.stelekit.ui.components

/** GitLab's precedent for the max source length it will attempt to render as a diagram. */
const val MAX_MERMAID_SOURCE_LENGTH = 2000

/**
 * Watchdog floor for an in-flight render, independent of Mermaid's own character cap.
 * [dev.stapler.stelekit.ui.components.MermaidEngineActor] widens this adaptively based on
 * recently observed render latency (see its own doc) — this is the minimum, used whenever
 * recent renders have been fast.
 */
const val MERMAID_RENDER_TIMEOUT_MS = 4000L

/**
 * One-time budget for the first render on a fresh engine, which pays GraalJS's cold-start
 * cost (5.6MB bundle eval + `mermaid.initialize()`) before rendering anything. Measured
 * 5.7s uncontended / 9.2s CPU-starved (2 cores); 30s covers >3x the starved case so slow
 * CI instances wait instead of flaking. Warm renders need no such budget (measured
 * 116–843ms across the same conditions — the steady-state floor above already covers ~5x).
 * A genuinely wedged cold `Context` costs one 30s timeout, then the actor swaps it.
 */
const val MERMAID_COLD_START_TIMEOUT_MS = 30_000L

/**
 * Hard ceiling the adaptive timeout in [dev.stapler.stelekit.ui.components.MermaidEngineActor]
 * never exceeds, however slow recent renders have been — a genuinely wedged GraalJS `Context`
 * (mermaid-js has documented hang bugs, see ADR-001's wasmJs risk section) must still eventually
 * be abandoned and swapped out, not waited on forever.
 */
const val MERMAID_RENDER_TIMEOUT_CEILING_MS = 20_000L

/** Hardened Mermaid securityLevel applied at every JS-execution site; never left at Mermaid's "loose" default. */
const val MERMAID_SECURITY_LEVEL = "strict"
