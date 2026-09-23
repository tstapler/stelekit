package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidSvgAspectRatioTest {
    @Test
    fun mermaidSvgAspectRatio_should_useViewBoxDimensions_when_viewBoxPresent() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="400" height="200">""" +
            "</svg>"
        assertEquals(2f, mermaidSvgAspectRatio(svg))
    }

    @Test
    fun mermaidSvgAspectRatio_should_useWidthHeightAttrs_when_viewBoxAbsent() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="150px"></svg>"""
        assertEquals(2f, mermaidSvgAspectRatio(svg))
    }

    @Test
    fun mermaidSvgAspectRatio_should_returnDefault_when_svgHasNoDimensions() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>"""
        assertEquals(MERMAID_DEFAULT_ASPECT_RATIO, mermaidSvgAspectRatio(svg))
    }

    @Test
    fun mermaidSvgAspectRatio_should_returnDefault_when_dimensionsAreZeroOrMalformed() {
        assertEquals(MERMAID_DEFAULT_ASPECT_RATIO, mermaidSvgAspectRatio("""<svg viewBox="0 0 0 0"></svg>"""))
        assertEquals(MERMAID_DEFAULT_ASPECT_RATIO, mermaidSvgAspectRatio("not even xml"))
        assertEquals(MERMAID_DEFAULT_ASPECT_RATIO, mermaidSvgAspectRatio(""))
    }
}
