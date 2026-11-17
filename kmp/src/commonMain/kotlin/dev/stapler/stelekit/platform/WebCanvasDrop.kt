// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/**
 * Where the web entry point leaves the canvas element's drop and paste handler for the sketch editor to pick up (Story 5.1.5b).
 * Common code cannot build it (it needs the DOM element), so `main` sets [host] once and every other target leaves it null.
 */
object WebCanvasDrop {
    var host: CanvasFileDropHost? = null
}
