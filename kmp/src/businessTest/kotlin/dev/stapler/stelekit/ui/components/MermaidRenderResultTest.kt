package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidRenderResultTest {
    private fun describe(result: MermaidRenderResult): String = when (result) {
        is MermaidRenderResult.Rendered -> "rendered:${result.svg}"
        is MermaidRenderResult.Failed -> "failed:${result.reason}"
        MermaidRenderResult.UnsupportedPlatform -> "unsupported"
    }

    @Test
    fun result_should_beExhaustivelyDistinguishable_when_matchedByWhen() {
        assertEquals("rendered:<svg/>", describe(MermaidRenderResult.Rendered("<svg/>")))
        assertEquals("failed:bad syntax", describe(MermaidRenderResult.Failed("bad syntax")))
        assertEquals("unsupported", describe(MermaidRenderResult.UnsupportedPlatform))
    }
}
