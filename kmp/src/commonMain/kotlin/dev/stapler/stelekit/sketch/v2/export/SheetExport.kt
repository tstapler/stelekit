// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.render.DrawOp

/**
 * A sheet on paper (E7): the SVG and PDF writers over the pages [PageComposer] lays out. One `SheetPrint` is one export: the
 * drawing's [content] (scene units) at the [plan]'s scale. [clip] is true when the drawing must not spill into the margins
 * (a tiled or overflowing page, a frame crop); [ink] colours the page furniture.
 */
class SheetPrint(val plan: SheetPlan, val content: List<DrawOp>, val clip: Boolean, val ink: String, val background: String?) {
    /** The pages of the export: every tile when [tiled], else the first page only. */
    fun pages(tiled: Boolean): List<List<DrawOp>> {
        val tiles = if (tiled && !plan.tooManyPages) plan.tiles else plan.tiles.take(1)
        val clipped = clip || tiles.size > 1
        return tiles.map { PageComposer.compose(plan, it, content, clipped, ink, tiled = tiles.size > 1) }
    }

    /** The one-page SVG: width and height in physical units, the viewBox in page units, and the `data-sketch-*` scale metadata. */
    fun svg(images: SvgImageSource = SvgImageSource.None): String {
        val setup = plan.setup
        val options = SvgExportOptions(background = background, padding = 0.0, physical = physical(setup))
        return SvgExporter.write(pages(false).single(), Bounds(0.0, 0.0, plan.pageWidth, plan.pageHeight), images, options)
    }

    fun pdf(tiled: Boolean, title: String): ByteArray {
        val setup = plan.setup
        return PdfWriter.write(pages(tiled), plan.pageWidth, plan.pageHeight, background, PdfInfo(title, "Scale ${setup.scale.ratio} on ${setup.paper.label} ${setup.orientation.name.lowercase()}"))
    }

    companion object {
        /** `8.5in` or `210mm`: the paper's own unit, so the number is the one printed on the ream. */
        fun physicalWidth(setup: SheetSetup): String = "${Micrometers.format(setup.paper.width(setup.orientation), setup.paper.imperial)}${Micrometers.unit(setup.paper.imperial)}"

        fun physicalHeight(setup: SheetSetup): String = "${Micrometers.format(setup.paper.height(setup.orientation), setup.paper.imperial)}${Micrometers.unit(setup.paper.imperial)}"

        /**
         * The documented metadata (see `docs/reference/sketch-format-v2.md`): `data-sketch-scale` is the drawing scale as
         * paper:real (`1:48`), `data-sketch-scene-unit` the real length one scene unit stands for, and `data-sketch-page-unit`
         * the physical length one viewBox unit is. A tool recovers a real length as `viewBox length * (scale real / paper)` page
         * units, since a page unit and a scene unit are the same sixteenth of an inch.
         */
        fun physical(setup: SheetSetup): SvgPhysical = SvgPhysical(
            physicalWidth(setup),
            physicalHeight(setup),
            listOf(
                "data-sketch-scale" to setup.scale.ratio,
                "data-sketch-scene-unit" to SCENE_UNIT,
                "data-sketch-page-unit" to SCENE_UNIT,
                "data-sketch-paper" to setup.paper.id,
                "data-sketch-orientation" to setup.orientation.name.lowercase(),
            ),
        )

        /** One scene unit is a sixteenth of an inch of real length, and so is one page unit. */
        const val SCENE_UNIT = "1/16in"
    }
}
