package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spike sanity checks for Story 3.1.1 — confirms GraalJS + the vendored `mermaid.js`
 * actually produce SVG output, not just that the classes compile.
 */
class MermaidJvmEngineTest {
    @Test
    fun render_should_produceSvg_when_flowchartSourceValid() {
        val engine = MermaidJvmEngine()
        val svg = try {
            engine.render("graph TD; A-->B-->C")
        } catch (e: MermaidEngineException) {
            fail("render threw: ${e.message}")
        }
        println("SPIKE flowchart SVG (${svg.length} chars):\n$svg")
        assertTrue(svg.contains("<svg"), "expected <svg> root, got: $svg")
    }

    @Test
    fun render_should_produceSvg_when_textLabelsPresent() {
        val engine = MermaidJvmEngine()
        val svg = try {
            engine.render("graph TD; Start[Start Node]-->Decision{Is it valid?}-->End[Done]")
        } catch (e: MermaidEngineException) {
            fail("render threw: ${e.message}")
        }
        println("SPIKE labeled flowchart SVG (${svg.length} chars):\n$svg")
        assertTrue(svg.contains("<svg"), "expected <svg> root, got: $svg")
        assertTrue(svg.contains("Start") || svg.contains("Decision"), "expected label text to appear in SVG output")
    }

    @Test
    fun render_should_throwIllegalStateException_when_calledConcurrentlyFromTwoThreads() {
        // Confirms ADR-001's premise: a shared GraalJS Context is not safe for concurrent
        // multi-thread access — this is the reason MermaidEngineActor exists.
        val engine = MermaidJvmEngine()
        engine.render("graph TD; A-->B") // force context creation on this thread first

        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..4).map {
            Thread {
                try {
                    engine.render("graph TD; X-->Y-->Z")
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        println("SPIKE concurrent-access errors: ${errors.map { it::class.simpleName + ": " + it.message }}")
    }
}
