// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.scene.FillStyleOption

/** The patch the style panel applies when this fill option is chosen; the row sets only the fill style (Task 2.2.2b). */
fun FillStyleOption.toPatch(): StylePatch = StylePatch(fillStyle = style)
